"""Real FastAPI route with deterministic provider for BE document review tests."""
import json
import os
import sys

import uvicorn
from fastapi.responses import PlainTextResponse
from pydantic import SecretStr

import app.main as main_module
from app.core.config import Settings, get_settings
from app.routers.document_review import get_review_client
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse


class FakeProvider:
    async def complete(self, request, *, timeout_seconds):
        payload = json.loads(request.messages[-1].content)
        if 'E2E_TIMEOUT' in payload['content']:
            raise ProviderError('model_timeout', input_tokens=3, output_tokens=1)
        refs = [item['reference_id'] for item in payload['references']]
        def patch(original, replacement):
            return {'type': 'PATCH', 'step': 'BODY', 'explanation': '수정', 'sources': [],
                    'category': 'READABILITY', 'severity': 'MINOR',
                    'original_text': original, 'replacement_text': replacement}
        if 'E2E_PATCH_DELETE' in payload['content']:
            suggestions = [patch(payload['content'], '')]
        elif 'E2E_PATCH_CONFLICT' in payload['content']:
            suggestions = [patch('first-token', 'second-token'), patch('second-token', 'done-token')]
        elif 'E2E_PATCH_VALID' in payload['content']:
            suggestions = [patch('red-item', 'scarlet-item'), patch('blue-item', 'azure-item')]
        elif 'E2E_BOM_PREFIX' in payload['content']:
            suggestions = [patch('\ufeff# E2E_BOM_PREFIX', '# updated'), patch('other-token', 'done-token')]
        else:
            suggestions = [{'type': 'COMMENT', 'step': 'BODY', 'explanation': '본문 검토 의견', 'sources': [],
                            'category': 'READABILITY', 'severity': 'MINOR'}]
        output = {'summary': '검토 완료', 'body_reviewed': True,
                  'reviewed_reference_ids': refs, 'suggestions': suggestions}
        return ProviderResponse(json.dumps(output, ensure_ascii=False), request.model, 11, 7)

settings = Settings(_env_file=None, ai_internal_token=SecretStr(os.environ['BEEZ_REVIEW_E2E_TOKEN']))
main_module.EmbeddingTokenCounter = lambda: None
main_module.app.dependency_overrides[get_settings] = lambda: settings
main_module.app.dependency_overrides[get_review_client] = lambda: LLMClient(FakeProvider())

@main_module.app.get('/__e2e_ready', include_in_schema=False)
async def ready():
    return PlainTextResponse(os.environ['BEEZ_REVIEW_E2E_NONCE'])

if __name__ == '__main__':
    uvicorn.run(main_module.app, host='127.0.0.1', port=int(sys.argv[1]),
                log_level='error', access_log=False)

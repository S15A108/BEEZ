-- pgvector: 결정·문서 임베딩 검색용.
-- 이후 마이그레이션에서 vector(N) 컬럼과 HNSW 인덱스를 추가한다 (N 은 ai 서버의 임베딩 모델에 맞춤).
CREATE EXTENSION IF NOT EXISTS vector;

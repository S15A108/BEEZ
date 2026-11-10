/** Live frontend smoke against a disposable #229 Spring integration-test fixture.
 * Input is a JSON file containing backendUrl, projectId, proposalId, email,
 * password, expectedStatus, expectedText and outputDir. Never use production data.
 * The Vite proxy and real browser session use the actual backend; no route mocks.
 */
import assert from 'node:assert/strict'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const frontend = path.join(root, 'frontend')
const require = createRequire(path.join(frontend, 'package.json'))
const { chromium } = require('@playwright/test')
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href)
const input = JSON.parse(await readFile(process.argv[2], 'utf8'))
const target = new URL(input.backendUrl)
assert(['127.0.0.1', 'localhost'].includes(target.hostname), 'Only a disposable local backend is allowed')
assert(/^\d+$/.test(String(input.projectId)) && /^\d+$/.test(String(input.proposalId)))
const output = path.resolve(input.outputDir)
await mkdir(output, { recursive: true })
process.env.VITE_API_MODE = 'live'
const vite = await createServer({
  root: frontend,
  configFile: path.join(frontend, 'vite.config.ts'),
  server: { host: '127.0.0.1', port: 0, proxy: { '/api': target.origin } },
})
let browser
let page
const evidence = { startedAt: new Date().toISOString(), mode: 'live', backend: target.origin, checks: [] }
try {
  await vite.listen()
  const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
  browser = await chromium.launch({ headless: true, channel: 'chromium' })
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const login = await context.request.post(`${origin}/api/auth/login`, {
    data: { email: input.email, password: input.password },
  })
  assert.equal(login.status(), 200, 'Real backend login must succeed')
  const endpoint = `/api/projects/${input.projectId}/change-proposals/${input.proposalId}`
  const anonymous = await browser.newContext()
  const unauthenticated = await anonymous.request.get(origin + endpoint)
  assert.equal(unauthenticated.status(), 401, 'Unauthenticated publication state must be denied')
  await anonymous.close()
  const outsider = await browser.newContext()
  const outsiderLogin = await outsider.request.post(`${origin}/api/auth/login`, {
    data: { email: input.outsiderEmail ?? 'outsider@test', password: input.password },
  })
  assert.equal(outsiderLogin.status(), 200)
  const forbidden = await outsider.request.get(origin + endpoint)
  assert.equal(forbidden.status(), 404, 'Another project user must not learn publication state')
  await outsider.close()
  evidence.checks.push({ name: 'actual-api-access-boundary', unauthenticated: 401, outsider: 404 })
  const detail = await context.request.get(origin + endpoint)
  assert.equal(detail.status(), 200)
  const response = await detail.json()
  const body = response.data ?? response
  assert.equal(body.published_analysis.status, input.expectedStatus)
  evidence.checks.push({ name: 'actual-proposal-api', status: body.published_analysis.status,
    evaluationStatus: body.published_analysis.evaluation_status ?? null })
  page = await context.newPage()
  const pageErrors = []
  const apiResponses = []
  page.on('pageerror', e => pageErrors.push(e.message))
  page.on('response', res => { if (res.url().includes(endpoint)) apiResponses.push(res.status()) })
  await page.goto(`${origin}/projects/${input.projectId}`)
  // This is a fresh real account, not the mock fixture's fixed account 11.
  await page.getByRole('button', { name: '건너뛰고 바로 시작하기', exact: true }).click({ timeout: 60000 })
  await page.getByRole('main', { name: '프로젝트 워크스페이스' }).waitFor({ timeout: 60000 })
  await page.getByRole('button', { name: '변경 제안', exact: true }).click()
  const dialog = page.getByRole('dialog')
  const title = body.proposal.title
  await dialog.getByRole('button').filter({ hasText: title }).first().click({ timeout: 30000 })
  const region = page.getByRole('region', { name: '발행 후 분석 상태' })
  await region.getByText(input.expectedText, { exact: true }).waitFor({ timeout: 30000 })
  assert((await region.innerText()).includes('발행 전 영향 분석과는 별도의 처리 상태'))
  const refreshResponsePromise = page.waitForResponse(
    res => new URL(res.url()).pathname === endpoint && res.request().method() === 'GET',
    { timeout: 30000 },
  )
  await region.getByRole('button', { name: '상태 새로고침' }).click()
  const refreshed = await refreshResponsePromise
  assert.equal(refreshed.status(), 200)
  const refreshedEnvelope = await refreshed.json()
  assert.equal((refreshedEnvelope.data ?? refreshedEnvelope).published_analysis.status, input.expectedStatus)
  await region.getByText(input.expectedText, { exact: true }).waitFor({ timeout: 30000 })
  assert(apiResponses.length >= 2 && apiResponses.every(s => s === 200), 'Screen must read and refresh the real proposal API')
  assert.deepEqual(pageErrors, [])
  await page.screenshot({ path: path.join(output, 'published-analysis-live.png'), fullPage: true })
  evidence.checks.push({ name: 'live-ui-and-refresh', text: input.expectedText, apiResponses, pageErrors })
  await dialog.getByRole('tab', { name: /^영향/ }).click()
  await dialog.getByText(/발행 전 영향 분석 결과예요/).waitFor()
  assert.equal(await dialog.getByRole('button', { name: '다시 분석', exact: true }).count(), 0)
  evidence.checks.push({ name: 'pre-publication-impact-kept-separate', passed: true })
  evidence.finishedAt = new Date().toISOString()
  evidence.passed = true
  await writeFile(path.join(output, 'ui-evidence.json'), JSON.stringify(evidence, null, 2) + '\n')
  console.log('229 live UI: actual API, status, refresh, and pre-publication distinction passed')
} catch (error) {
  evidence.passed = false
  evidence.failure = String(error.message)
  await page?.screenshot({ path: path.join(output, 'live-ui-failure.png'), fullPage: true }).catch(() => {})
  await writeFile(path.join(output, 'ui-evidence.json'), JSON.stringify(evidence, null, 2) + '\n')
  throw error
} finally {
  await browser?.close()
  await vite.close()
}

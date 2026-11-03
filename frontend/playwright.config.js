import { defineConfig, devices } from '@playwright/test'

// 冒烟测试：自动起一个内存库后端（8090，全新示例数据）和一个前端开发服务器（5174），跑完即停。
// 用例按文件顺序串行执行，后面的用例会用到前面留下的状态（例如经销商下的单）。
const API = 'http://localhost:8090'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5174',
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1360, height: 900 } } }],
  webServer: [
    {
      command: 'mvn -q -B spring-boot:run -Dspring-boot.run.profiles=e2e',
      cwd: '../backend',
      url: `${API}/api/auth/roles`,
      timeout: 240_000,
      reuseExistingServer: false,
      stdout: 'ignore',
      stderr: 'pipe'
    },
    {
      command: 'npm run dev -- --port 5174 --strictPort',
      url: 'http://localhost:5174',
      env: { VITE_API_TARGET: API },
      timeout: 60_000,
      reuseExistingServer: false
    }
  ]
})

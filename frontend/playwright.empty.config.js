import { defineConfig, devices } from '@playwright/test'

// 空库走查：和正式上线一样，没有示例数据、开启强制改密，所有准备工作都从页面做。
// 冒烟测试（playwright.config.js）带示例数据、关闭强制改密，测不到上线第一天。端口和它错开，可以分别跑。
const API = 'http://localhost:8091'

export default defineConfig({
  testDir: './e2e-empty',
  fullyParallel: false,
  workers: 1,
  retries: 0, // 用例之间依赖前面留下的数据，单独重试没有意义
  timeout: 180_000, // 一个用例要切换几个岗位，每次登录都算一遍密码；CI 比开发机慢 3 倍左右
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-empty' }]] : 'list',
  outputDir: 'test-results-empty',
  use: {
    baseURL: 'http://localhost:5175',
    locale: 'zh-CN',
    actionTimeout: 10_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1360, height: 900 } } }],
  webServer: [
    {
      command: "mvn -q -B spring-boot:run -Dspring-boot.run.profiles=e2e '-Dspring-boot.run.arguments=--server.port=8091 --scm.demo-data=false --scm.security.force-password-change=true --spring.datasource.url=jdbc:h2:mem:scm-empty;MODE=MySQL;DB_CLOSE_DELAY=-1'",
      cwd: '../backend',
      url: `${API}/api/auth/roles`,
      timeout: 240_000,
      reuseExistingServer: false,
      stdout: 'ignore',
      stderr: 'pipe'
    },
    {
      command: 'npm run dev -- --port 5175 --strictPort',
      url: 'http://localhost:5175',
      env: { VITE_API_TARGET: API },
      timeout: 60_000,
      reuseExistingServer: false
    }
  ]
})

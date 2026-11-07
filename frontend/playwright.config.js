import { defineConfig, devices } from '@playwright/test'

// 冒烟测试：自动起一个内存库后端（8090，全新示例数据）和一个前端开发服务器（5174），跑完即停。
// 用例按文件顺序串行执行，后面的用例会用到前面留下的状态（例如经销商下的单）。
const API = 'http://localhost:8090'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0, // 用例串行、依赖前面留下的数据，重试时库不干净，只会多出一个看似独立的失败
  timeout: 90_000, // 有的用例要切换几个岗位，每次登录都算一遍密码；CI 比开发机慢 3 倍左右，45 秒曾被顶满
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
      // CI 上把后端输出打到任务日志里：Maven 用了 -q，报错只在标准输出，丢掉的话启动失败时什么都看不到
      stdout: process.env.CI ? 'pipe' : 'ignore',
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

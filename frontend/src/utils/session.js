import { reactive } from 'vue'

// active：这个页面已经有登录用户；expired：用着用着令牌失效了，等用户重新输入密码（ReloginDialog）。
// 单独一个文件，因为 api.js 和 stores/auth.js 都要用，互相引用会循环。
export const session = reactive({ active: false, expired: false })

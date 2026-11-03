import { computed, ref, unref, watch } from 'vue'

/**
 * 列表的关键字筛选 + 前端分页。后端列表已限制在几百行以内，前端分页足够。
 * match(row, keyword) 返回是否命中；extra 是其它筛选条件（computed 或函数），任一变化都回到第 1 页。
 */
export function usePaged(rows, { match, extra, pageSize = 20 } = {}) {
  const keyword = ref('')
  const page = ref(1)
  const size = ref(pageSize)

  const filtered = computed(() => {
    const kw = keyword.value.trim().toLowerCase()
    let list = unref(rows) || []
    if (kw && match) list = list.filter((r) => match(r, kw))
    if (extra) list = list.filter((r) => extra(r))
    return list
  })
  const total = computed(() => filtered.value.length)
  const paged = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))

  watch([keyword, size, () => (extra ? filtered.value.length : 0)], () => { page.value = 1 })
  // 数据刷新后若当前页已超出范围，退回最后一页
  watch(total, (t) => {
    const last = Math.max(1, Math.ceil(t / size.value))
    if (page.value > last) page.value = last
  })

  return { keyword, page, size, filtered, paged, total }
}

/** 把若干字段拼起来做不区分大小写的包含匹配。 */
export function textMatch(...fields) {
  return (row, kw) => fields.map((f) => (typeof f === 'function' ? f(row) : row[f]) ?? '').join(' ').toLowerCase().includes(kw)
}

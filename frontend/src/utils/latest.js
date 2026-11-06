/**
 * 同一个查询连发多次时，只让最后一次生效。网络抖动时先发的请求可能晚回来，盖掉后面的结果：
 * 盘点表显示 A 仓、提交的却是 B 仓；召回预览是上一个批号的客户名单。
 *
 * const stockReq = latestOnly()
 * const current = stockReq()              // 每次发请求前取一个
 * const rows = await http.get(...)
 * if (current()) stock.value = rows       // 已经有更新的请求，就丢掉这次的结果
 */
export function latestOnly() {
  let seq = 0
  return () => {
    const id = ++seq
    return () => id === seq
  }
}

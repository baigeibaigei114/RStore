/**
 * 统一后端响应封装类型
 * 后端所有 API 返回均使用此结构包装，响应拦截器自动解包提取 data
 * @template T - data 字段的实际数据类型
 */
export interface ApiResult<T> {
  /** 业务状态码，200 表示成功，非 200 表示业务异常 */
  code: number
  /** 业务消息，成功时通常为 "success"，失败时描述错误原因 */
  message: string
  /** 实际业务数据，响应拦截器会自动提取此字段返回给调用方 */
  data: T
}

/**
 * 通用分页结果类型
 * 所有分页查询接口均返回此结构
 * @template T - records 数组中单个元素的类型
 */
export interface PageResult<T> {
  /** 当前页的数据记录列表 */
  records: T[]
  /** 符合查询条件的总记录数 */
  total: number
  /** 当前页码，从 1 开始 */
  pageNum: number
  /** 每页记录数 */
  pageSize: number
}

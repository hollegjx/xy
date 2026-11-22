// 由 MockMvc 导出本工程的 springdoc 文档；不会访问运行中的后端。
import { generateService } from '@umijs/openapi'
import fs from 'node:fs/promises'
import console from 'node:console'
import process from 'node:process'
import path from 'node:path'
const root = path.resolve(import.meta.dirname, '..')
const special = new Set([
  'generate',
  'getPreview',
  'getFeaturedCover',
  'downloadSource',
  'listFeaturedApps',
])
generateService({
  schemaPath: path.join(root, '../backend/target/xy-openapi.json'),
  serversPath: path.join(root, 'src/service'),
  projectName: 'generated',
  namespace: 'XYApi',
  apiPrefix: "'/api'",
  requestImportStatement: "import request from '@/request'",
  requestOptionsType: "import('axios').AxiosRequestConfig",
  dataFields: ['data'],
  hook: {
    afterOpenApiDataInited(doc) {
      for (const [route, methods] of Object.entries(doc.paths)) {
        for (const [method, op] of Object.entries(methods)) {
          if (special.has(op.operationId)) {
            delete methods[method]
            continue
          }
          // 浏览器从 URL 获取十进制 ID，不能用 JS Number 中转。
          for (const p of op.parameters ?? []) {
            // 省略可选查询时沿用后端默认值，客户端不把它固化在 URL。
            if (p.in === 'query' && p.schema) delete p.schema.default
            if (p.in === 'path' || ['id', 'userId', 'beforeId'].includes(p.name))
              p.schema = { type: 'string' }
          }
        }
        if (!Object.keys(methods).length) delete doc.paths[route]
      }
      return doc
    },
    // openapi2ts 的解析器按 OpenAPI 3.0 处理类型；这里保留 springdoc 3.1 的显式 null。
    customType(schema, namespace, original) {
      if (Array.isArray(schema?.type))
        return schema.type
          .map((type) => (type === 'null' ? 'null' : original({ ...schema, type }, namespace)))
          .join(' | ')
      if (schema?.type === 'null') return 'null'
    },
    customFileNames(op, route) {
      return [route.startsWith('/users') ? 'user' : route === '/health' ? 'health' : 'app']
    },
    customFunctionName(op) {
      return op.operationId
    },
  },
})
  .then(async () => {
    const dir = path.join(root, 'src/service/generated')
    for (const name of await fs.readdir(dir)) {
      const file = path.join(dir, name)
      const source = (await fs.readFile(file, 'utf8'))
        .replace(/^\/\/ @ts-ignore\r?\n/gm, '')
        .replace(/^\/\* eslint-disable \*\/\r?\n/gm, '')
        .replace(/: any\b/g, ': unknown')
      await fs.writeFile(file, source)
    }
  })
  .catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })

import { describe, expect, it } from 'vitest'
import { renderAssistantMarkdown as render } from './assistantMarkdown'

describe('工作台的模型回复', () => {
  it('把说明排成段落、列表和标题，为已知语言的代码着色', () => {
    const html = render('## 修改说明\n\n- 保留首页\n\n```js\nconst page = 2\n```')
    expect(html).toContain('<h2>修改说明</h2>')
    expect(html).toContain('<li>保留首页</li>')
    expect(html).toContain('hljs-keyword')
  })

  it('将原生网站源码作为代码展示，模型图片不会在工作台发起请求', () => {
    expect(render('<html><img src="/example.jpg"></html>')).toContain('&lt;')
    const html = render('说明：<img src="/example.jpg">\n\n![封面](https://example.test/p.jpg)')
    expect(html).toContain('封面')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('https://example.test/p.jpg')
    expect(render('```unknown\n<button>源码</button>\n```')).toContain('&lt;button&gt;')
  })
})

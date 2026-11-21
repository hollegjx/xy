import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import css from 'highlight.js/lib/languages/css'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'

for (const [name, language] of Object.entries({ javascript, typescript, css, json, xml })) {
  hljs.registerLanguage(name, language)
}
hljs.registerAliases('vue', { languageName: 'xml' })

function highlight(source: string, language: string): string {
  return hljs.getLanguage(language)
    ? hljs.highlight(source, { language, ignoreIllegals: true }).value
    : md.utils.escapeHtml(source)
}

const md = new MarkdownIt({ html: false, breaks: true, highlight })
md.validateLink = (href) => /^https?:\/\//i.test(href)
md.renderer.rules.link_open = (tokens, i, options, _env, renderer) => {
  tokens[i]!.attrSet('target', '_blank')
  tokens[i]!.attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, i, options)
}
// 工作台显示模型回复；只有隔离的作品预览才加载作品里的图片。
md.renderer.rules.image = (tokens, i, options, env, renderer) =>
  md.utils.escapeHtml(renderer.renderInlineAsText(tokens[i]!.children ?? [], options, env))

export function renderAssistantMarkdown(source: string): string {
  const trimmed = source.trimStart()
  const language = /^<(?:!doctype\b|[\w-]+[\s/>])/i.test(trimmed)
    ? 'html'
    : /^\{\s*(?:"|$)/.test(trimmed)
      ? 'json'
      : ''
  return language
    ? '<pre><code>' + highlight(source, language) + '</code></pre>'
    : md.render(source)
}

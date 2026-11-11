import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import './markdown.css'

const remarkPlugins = [remarkGfm, remarkMath]
const rehypePlugins = [rehypeKatex]

/**
 * Renders assistant output as Markdown with GitHub tables and KaTeX math.
 * Raw HTML is not rendered, so model output cannot inject page markup.
 */
export default function MarkdownMessage({ content }) {
  return (
    <div className="markdown-message">
      <ReactMarkdown remarkPlugins={remarkPlugins} rehypePlugins={rehypePlugins}>
        {content || ''}
      </ReactMarkdown>
    </div>
  )
}

// Permanent presentation-layer privacy guardrail.
//
// Prism's API intentionally returns model routing metadata so operators and
// API consumers can audit it. The chat UI, however, can be shown to end users
// who should not see upstream model names, creators, or provider routes.
// This module masks those details immediately before rendering. It is a UI
// privacy control, not a security boundary: a caller who can read the network
// response can still see the original fields.

const MODEL_FACT_LINE = /^(\s*[-*]\s*\*\*(?:model name|creator|family|variant|gateway|api surface|modalities|provider route|requested model\/alias|tier|task route|fallback chain|fallback used|pricing|context window \/ parameter count)\s*:?\*\*\s*:?)\s*.*$/gim

const MODEL_ID = /\b(?:gemini|gemma|gpt|claude|llama|nemotron|mistral|mixtral|deepseek|qwen|grok|command(?:-r)?|phi|yi)[\w/:-]*(?:\.[\w:-]+)*/gi
const PROVIDER = /\b(?:google|openai|anthropic|nvidia|meta|mistral ai|deepseek|alibaba|xai|cohere|openrouter|prism mock provider)\b/gi
const MOCK_ROUTE = /\b(?:alpha|beta)(?:-(?:small|large))?\b/gi

/** Masks model/provider details in assistant text before it is rendered. */
export function redactModelDetails(text) {
  if (!text) return text
  let out = String(text).replace(MODEL_FACT_LINE, (_, prefix) => `${prefix.trimEnd()} [hidden by privacy guardrail]`)
  out = out.replace(MODEL_ID, '[model hidden]')
  out = out.replace(PROVIDER, '[provider hidden]')
  out = out.replace(MOCK_ROUTE, '[model hidden]')
  return out
}

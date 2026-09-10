const VALID_TONES = new Set(['cyan', 'mint', 'amber'])

export function buildDialogueSegments(text = '', highlights = []) {
  const source = String(text)
  const tokens = highlights
    .filter(item => item?.text && VALID_TONES.has(item.tone))
    .map(item => ({ text: String(item.text), tone: item.tone }))

  if (!source || tokens.length === 0) return [{ text: source, tone: null }]

  const segments = []
  let cursor = 0

  while (cursor < source.length) {
    let match = null
    for (const token of tokens) {
      const index = source.indexOf(token.text, cursor)
      if (index < 0) continue
      if (!match || index < match.index || (index === match.index && token.text.length > match.text.length)) {
        match = { ...token, index }
      }
    }

    if (!match) {
      segments.push({ text: source.slice(cursor), tone: null })
      break
    }
    if (match.index > cursor) segments.push({ text: source.slice(cursor, match.index), tone: null })
    segments.push({ text: match.text, tone: match.tone })
    cursor = match.index + match.text.length
  }

  return segments
}

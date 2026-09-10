export const CHARACTER_MANIFEST = Object.freeze({
  anan: Object.freeze({
    id: 'anan',
    name: '阿南',
    callsign: 'ANAN / FIELD GUIDE',
    side: 'left',
    expressions: Object.freeze({
      default: '/tutorial/characters/anan-default.png',
      greeting: '/tutorial/characters/anan-greeting.png',
      guide: '/tutorial/characters/anan-guide.png',
      awkward: '/tutorial/characters/anan-awkward.png'
    })
  }),
  cheng: Object.freeze({
    id: 'cheng',
    name: '程昱',
    callsign: 'CHENG YU / SYSTEMS',
    side: 'right',
    expressions: Object.freeze({
      default: '/tutorial/characters/cheng-default.png',
      analysis: '/tutorial/characters/cheng-analysis.png',
      confident: '/tutorial/characters/cheng-confident.png',
      facepalm: '/tutorial/characters/cheng-facepalm.png'
    })
  })
})

export function getCharacterExpression(speaker, expression = 'default') {
  const character = CHARACTER_MANIFEST[speaker]
  if (!character) return null
  return character.expressions[expression] || character.expressions.default
}

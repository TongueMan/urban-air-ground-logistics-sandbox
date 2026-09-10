export const HEFEI_CITY_CENTER = [117.2272, 31.8206, 0]

// Shared by the situation and logistics mission maps. Keep the land/building
// palette deliberately dark so the operational overlays remain legible.
export const BAIDU_CYBER_MAP_STYLE = [
  { featureType: 'all', elementType: 'geometry', stylers: { visibility: 'on', color: '#071326ff' } },
  { featureType: 'background', elementType: 'geometry', stylers: { visibility: 'on', color: '#071326ff' } },
  { featureType: 'land', elementType: 'geometry', stylers: { visibility: 'on', color: '#071326ff' } },
  { featureType: 'water', elementType: 'geometry', stylers: { visibility: 'on', color: '#071c32ff' } },
  { featureType: 'green', elementType: 'geometry', stylers: { visibility: 'on', color: '#0b2c32ff' } },
  { featureType: 'manmade', elementType: 'geometry', stylers: { visibility: 'on', color: '#0a182cff' } },
  { featureType: 'residential', elementType: 'geometry', stylers: { visibility: 'on', color: '#0b1b31ff' } },
  { featureType: 'education', elementType: 'geometry', stylers: { visibility: 'on', color: '#0d2135ff' } },
  { featureType: 'medical', elementType: 'geometry', stylers: { visibility: 'on', color: '#152238ff' } },
  { featureType: 'business', elementType: 'geometry', stylers: { visibility: 'on', color: '#0d1d34ff' } },
  { featureType: 'shopping', elementType: 'geometry', stylers: { visibility: 'on', color: '#112038ff' } },
  { featureType: 'entertainment', elementType: 'geometry', stylers: { visibility: 'on', color: '#10243aff' } },
  { featureType: 'sports', elementType: 'geometry', stylers: { visibility: 'on', color: '#0b3037ff' } },
  { featureType: 'scenicspots', elementType: 'geometry', stylers: { visibility: 'on', color: '#0b2932ff' } },
  { featureType: 'transportation', elementType: 'geometry', stylers: { visibility: 'on', color: '#0c1a2eff' } },
  { featureType: 'building', elementType: 'geometry.fill', stylers: { visibility: 'on', color: '#1a2948ff' } },
  { featureType: 'building', elementType: 'geometry.topfill', stylers: { visibility: 'on', color: '#2b3c64ff' } },
  { featureType: 'building', elementType: 'geometry.sidefill', stylers: { visibility: 'on', color: '#10182dff' } },
  { featureType: 'building', elementType: 'geometry.stroke', stylers: { visibility: 'on', color: '#29466fff' } },
  { featureType: 'road', elementType: 'geometry.fill', stylers: { visibility: 'on', color: '#162640ff' } },
  { featureType: 'road', elementType: 'geometry.stroke', stylers: { visibility: 'on', color: '#315a7cff' } },
  { featureType: 'highway', elementType: 'geometry.fill', stylers: { visibility: 'on', color: '#284061ff' } },
  { featureType: 'highway', elementType: 'geometry.stroke', stylers: { visibility: 'on', color: '#4ac6f4ff' } },
  { featureType: 'arterial', elementType: 'geometry.fill', stylers: { visibility: 'on', color: '#172d4aff' } },
  { featureType: 'arterial', elementType: 'geometry.stroke', stylers: { visibility: 'on', color: '#2587b1ff' } },
  { featureType: 'local', elementType: 'geometry.fill', stylers: { visibility: 'on', color: '#112239ff' } },
  { featureType: 'local', elementType: 'geometry.stroke', stylers: { visibility: 'on', color: '#25516eff' } },
  { featureType: 'railway', elementType: 'geometry', stylers: { visibility: 'on', color: '#294d6cff' } },
  { featureType: 'subway', elementType: 'geometry', stylers: { visibility: 'on', color: '#245f7fff' } },
  { featureType: 'roadarrow', elementType: 'labels.icon', stylers: { visibility: 'off' } },
  { featureType: 'highwaysign', elementType: 'labels', stylers: { visibility: 'off' } },
  { featureType: 'districtlabel', elementType: 'labels.text.fill', stylers: { visibility: 'on', color: '#92b8d8ff' } },
  { featureType: 'districtlabel', elementType: 'labels.text.stroke', stylers: { visibility: 'on', color: '#061326ff' } },
  { featureType: 'poilabel', elementType: 'labels', stylers: { visibility: 'off' } }
]

export function createBaiduCyberProvider(mapvthree, THREE, ak) {
  mapvthree.BaiduMapConfig.ak = ak
  return new mapvthree.BaiduVectorTileProvider({
    ak,
    placeholderColor: new THREE.Color('#071326'),
    styleJson: BAIDU_CYBER_MAP_STYLE,
    displayOptions: { base: true, link: true, building: true, poi: false, flat: false }
  })
}

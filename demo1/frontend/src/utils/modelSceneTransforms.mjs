import * as THREE from 'three'

/**
 * Put imported content under a neutral transform root before callers scale or
 * rotate it. Keeping the centring translation on the child makes that offset
 * participate in the parent's transform instead of remaining in source units.
 */
export function centerSceneForTransform(scene, center) {
  const transformRoot = new THREE.Group()
  scene.position.sub(center)
  transformRoot.add(scene)
  return transformRoot
}

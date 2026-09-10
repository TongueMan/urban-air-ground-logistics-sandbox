import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ACTIVE_MODEL_ROLES, MODEL_ASSETS, getModelAsset } from '../src/config/modelAssets.mjs'

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const ids = Object.keys(MODEL_ASSETS)
const paths = new Set()
const failures = []

for (const id of ids) {
  const asset = MODEL_ASSETS[id]
  if (asset.id !== id) failures.push(`${id}: registry key and asset.id differ`)
  if (!asset.path.startsWith('models/') || !asset.path.endsWith('.glb')) failures.push(`${id}: invalid public GLB path`)
  if (paths.has(asset.path)) failures.push(`${id}: duplicate path ${asset.path}`)
  paths.add(asset.path)

  let bytes
  try {
    bytes = readFileSync(join(frontendRoot, 'public', ...asset.path.split('/')))
  } catch (error) {
    failures.push(`${id}: missing file (${error.code || error.message})`)
    continue
  }

  const magic = bytes.toString('ascii', 0, 4)
  const version = bytes.readUInt32LE(4)
  const declaredLength = bytes.readUInt32LE(8)
  const jsonLength = bytes.readUInt32LE(12)
  const jsonChunkType = bytes.toString('ascii', 16, 20)
  if (magic !== 'glTF' || version !== 2 || declaredLength !== bytes.length || jsonChunkType !== 'JSON') {
    failures.push(`${id}: invalid GLB 2.0 header`)
    continue
  }

  if (asset.bytes !== bytes.length) failures.push(`${id}: byte length changed (${asset.bytes} -> ${bytes.length})`)
  const sha256 = createHash('sha256').update(bytes).digest('hex')
  if (asset.sha256 !== sha256) failures.push(`${id}: SHA-256 changed`)

  try {
    const gltf = JSON.parse(bytes.toString('utf8', 20, 20 + jsonLength))
    const actual = {
      nodes: gltf.nodes?.length || 0,
      meshes: gltf.meshes?.length || 0,
      materials: gltf.materials?.length || 0,
      textures: gltf.textures?.length || 0,
      animations: gltf.animations?.length || 0,
      skins: gltf.skins?.length || 0
    }
    for (const [field, value] of Object.entries(actual)) {
      if (asset.scene[field] !== value) failures.push(`${id}: ${field} changed (${asset.scene[field]} -> ${value})`)
    }
    if (asset.extensions) {
      const expected = [...asset.extensions].sort()
      const actualExtensions = [...(gltf.extensionsUsed || [])].sort()
      if (JSON.stringify(expected) !== JSON.stringify(actualExtensions)) {
        failures.push(`${id}: extensions changed (${expected.join(', ')} -> ${actualExtensions.join(', ')})`)
      }
    }
  } catch (error) {
    failures.push(`${id}: invalid JSON chunk (${error.message})`)
  }
}

for (const [role, id] of Object.entries(ACTIVE_MODEL_ROLES)) {
  try {
    if (getModelAsset(id).lifecycle !== 'active') failures.push(`${role}: role points to non-active asset ${id}`)
  } catch (error) {
    failures.push(`${role}: ${error.message}`)
  }
}

if (failures.length) {
  console.error(`3D model library verification failed:\n- ${failures.join('\n- ')}`)
  process.exitCode = 1
} else {
  const totalBytes = Object.values(MODEL_ASSETS).reduce((sum, asset) => sum + asset.bytes, 0)
  console.log(`3D model library verified: ${ids.length} GLB assets, ${(totalBytes / 1024 / 1024).toFixed(1)} MiB total.`)
}

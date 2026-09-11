import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { cpSync, createReadStream, existsSync, mkdirSync, statSync } from 'node:fs'
import { dirname, extname, isAbsolute, relative, resolve } from 'node:path'

const mapvAssetsSource = resolve('node_modules/@baidumap/mapv-three/dist/assets')
const mapvAssetPaths = Object.freeze([
  'textures/water/foam_noise.webp',
  'models/effect/diamond.glb',
  'workers/BaiduVectorParser.worker-c827f410.js'
])
const mapvAssetPathSet = new Set(mapvAssetPaths)

function serveDirectory(source, request, response, next, allowedPaths = null) {
  const relativePath = decodeURIComponent(String(request.url || '').split('?')[0]).replace(/^\/+/, '')
  if (allowedPaths && !allowedPaths.has(relativePath)) return next()
  const assetPath = resolve(source, relativePath)
  const resolvedRelative = relative(source, assetPath)
  if (resolvedRelative.startsWith('..') || isAbsolute(resolvedRelative)) return next()
  if (!existsSync(assetPath) || !statSync(assetPath).isFile()) return next()
  const mimeTypes = { '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.wasm': 'application/wasm', '.glb': 'model/gltf-binary', '.bin': 'application/octet-stream' }
  response.setHeader('Content-Type', mimeTypes[extname(assetPath).toLowerCase()] || 'application/octet-stream')
  createReadStream(assetPath).pipe(response)
}

function mapvThreeAssets() {
  return {
    name: 'skyfleet-mapv-assets',
    configureServer(server) {
      server.middlewares.use('/mapvthree/assets', (request, response, next) => serveDirectory(mapvAssetsSource, request, response, next, mapvAssetPathSet))
    },
    writeBundle(options) {
      const outputRoot = resolve(options.dir || 'dist', 'mapvthree/assets')
      mapvAssetPaths.forEach(relativePath => {
        const destination = resolve(outputRoot, relativePath)
        mkdirSync(dirname(destination), { recursive: true })
        cpSync(resolve(mapvAssetsSource, relativePath), destination, { force: true })
      })
    }
  }
}

export default defineConfig(({ mode }) => {
  const env = {
    ...loadEnv(mode, resolve(process.cwd(), '..'), ''),
    ...loadEnv(mode, process.cwd(), '')
  }
  return {
    plugins: [vue(), mapvThreeAssets()],
    define: {
      'import.meta.env.VITE_BAIDU_MAP_AK': JSON.stringify(env.VITE_BAIDU_MAP_AK || env.FRONTEND_BAIDU_MAP_AK || '')
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: env.VITE_API_TARGET || 'http://localhost:8095', changeOrigin: true }
      }
    }
  }
})

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { cpSync, createReadStream, existsSync, statSync } from 'node:fs'
import { extname, resolve } from 'node:path'

const mapvAssetsSource = resolve('node_modules/@baidumap/mapv-three/dist/assets')

function mapvThreeAssets() {
  return {
    name: 'zhixun-mapv-assets',
    configureServer(server) {
      server.middlewares.use('/mapvthree/assets', (request, response, next) => {
        const relativePath = decodeURIComponent(String(request.url || '').split('?')[0]).replace(/^\/+/, '')
        const assetPath = resolve(mapvAssetsSource, relativePath)
        if (!assetPath.startsWith(mapvAssetsSource) || !existsSync(assetPath) || !statSync(assetPath).isFile()) return next()
        const mimeTypes = { '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.wasm': 'application/wasm', '.glb': 'model/gltf-binary', '.bin': 'application/octet-stream' }
        response.setHeader('Content-Type', mimeTypes[extname(assetPath).toLowerCase()] || 'application/octet-stream')
        createReadStream(assetPath).pipe(response)
      })
    },
    writeBundle(options) {
      cpSync(mapvAssetsSource, resolve(options.dir || 'dist', 'mapvthree/assets'), { recursive: true, force: true })
    }
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(), mapvThreeAssets()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: env.VITE_API_TARGET || 'http://localhost:8095', changeOrigin: true },
        '/media': { target: env.VITE_MEDIA_TARGET || 'http://localhost:8889', changeOrigin: true, rewrite: path => path.replace(/^\/media/, '') }
      }
    }
  }
})


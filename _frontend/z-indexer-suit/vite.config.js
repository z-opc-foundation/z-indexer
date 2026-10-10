import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

// z-indexer 独立运行壳。dev 3035；proxy /index → z-indexer server（8888）。
export default defineConfig({
    plugins: [react()],
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'] ,
        alias: process.env.LOCAL_SIBLINGS === '1' ? { '@yuku123/z-indexer-component': '../z-indexer-component/src' } : {}},
    server: {
        port: 3035,
        fs: {allow: ['..']},
        proxy: {'/index': {target: 'http://localhost:8888', changeOrigin: true}},
    },
})

import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

// z-indexer 组件层双入口（lead 005 §1.2/§9）：'.' 桶导出 + './pages' manifest
export default defineConfig({
    plugins: [safeEmptyOutDir(), react()],
    build: {
        lib: {
            entry: {
                index: path.resolve(__dirname, 'src/index.js'),
                pages: path.resolve(__dirname, 'src/pages.js'),
            },
            formats: ['es'],
        },
        outDir: 'dist',
        emptyOutDir: false,
        rollupOptions: {
            external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom',
                       'antd', '@ant-design/icons', 'axios', '@yuku123/z-frontend-common',
                       // 基座的 /ui、/ui/echarts 子路径也要 external —— rollup 的 external 是
                       // 精确字符串匹配而非前缀匹配，漏掉子路径会把基座 UI 组件复制进本包 dist。
                       '@yuku123/z-frontend-common/ui', '@yuku123/z-frontend-common/ui/echarts'],
        },
    },
})

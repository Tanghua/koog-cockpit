import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
export default defineConfig({ base: '/assets/', plugins: [react()], build: { target:'chrome101', cssTarget:'chrome101', outDir: '../cockpit-ui/src/main/assets', emptyOutDir:true } })

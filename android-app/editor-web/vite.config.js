import { defineConfig } from "vite";
import { babel } from "@rollup/plugin-babel";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  publicDir: "public",
  define: {
    "process.env.NODE_ENV": '"production"',
    "process.env": "{}"
  },
  plugins: [
    react({
      include: [/src\/.*\.js$/]
    })
  ],
  esbuild: {
    target: "es2015"
  },
  build: {
    outDir: "../app/src/main/assets/app",
    emptyOutDir: true,
    assetsDir: "assets",
    target: "es2015",
    sourcemap: false,
    lib: {
      entry: "src/main.js",
      name: "QthingApp",
      formats: ["iife"],
      fileName: (format) => `app.${format}.js`
    },
    rollupOptions: {
      plugins: [
        babel({
          babelHelpers: "bundled",
          extensions: [".js", ".mjs"],
          include: [
            /src\/.*\.(js|mjs)$/,
            /node_modules\/(@codemirror|codemirror|@lezer)\/.*\.(js|mjs)$/
          ],
          babelrc: false,
          configFile: false,
          presets: [
            [
              "@babel/preset-react",
              {
                runtime: "automatic"
              }
            ],
            [
              "@babel/preset-env",
              {
                targets: {
                  chrome: "64"
                }
              }
            ]
          ],
          plugins: ["@babel/plugin-transform-unicode-property-regex"],
          compact: true
        })
      ],
      output: {
        assetFileNames: (assetInfo) => {
          if (assetInfo.name && assetInfo.name.endsWith(".css")) return "assets/app.css";
          return "assets/[name][extname]";
        },
        inlineDynamicImports: true
      }
    }
  }
});

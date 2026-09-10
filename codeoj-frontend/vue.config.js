const { defineConfig } = require("@vue/cli-service");
const MonacoWebpackPlugin = require("monaco-editor-webpack-plugin");
const Components = require("unplugin-vue-components/webpack");
const { ArcoResolver } = require("unplugin-vue-components/resolvers");

module.exports = defineConfig({
  transpileDependencies: true,
  chainWebpack(config) {
    config
      .plugin("components")
      .use(
        Components({
          resolvers: [ArcoResolver()],
          // 模板中的 a-* 组件由插件按需注册，替换 main.ts 的全量 app.use(ArcoVue)
        })
      );
    config.plugin("monaco").use(
      new MonacoWebpackPlugin({
        // 仅打包项目实际使用的语言，避免 Monaco 全量语言包（100+ 种）导致首屏体积过大
        languages: ["java", "cpp", "go"],
      })
    );
    config.plugin("html").tap((args) => {
      args[0].title = "CodeOJ";
      return args;
    });
  },
});

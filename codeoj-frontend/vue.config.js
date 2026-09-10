const { defineConfig } = require("@vue/cli-service");
const MonacoWebpackPlugin = require("monaco-editor-webpack-plugin");

module.exports = defineConfig({
  transpileDependencies: true,
  chainWebpack(config) {
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

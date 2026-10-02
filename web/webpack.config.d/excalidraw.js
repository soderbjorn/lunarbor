// Excalidraw (DrawingEditor.kt) ships as strict ES modules that import
// `roughjs/bin/rough` and friends without a file extension; let webpack
// resolve those the CommonJS way.
config.module.rules.push({
    test: /\.m?js$/,
    resolve: { fullySpecified: false },
});

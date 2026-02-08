const path = require('path');

module.exports = {
  entry: {
    'GitProjectsConfig': './src/GitProjectsConfig.tsx',
    'GitUsersConfig': './src/GitUsersConfig.tsx'
  },
  output: {
    path: process.env.GIT_GATEWAY_MOUNTED_PATH || path.resolve(__dirname, '../../../git-gateway/src/main/resources/mounted'),
    filename: '[name].js',
    library: '[name]',
    libraryTarget: 'umd',
    umdNamedDefine: true,
    globalObject: 'this'
  },
  resolve: {
    extensions: ['.ts', '.tsx', '.js', '.jsx']
  },
  module: {
    rules: [
      {
        test: /\.(ts|tsx)$/,
        exclude: /node_modules/,
        use: {
          loader: 'babel-loader',
          options: {
            presets: [
              '@babel/preset-env',
              '@babel/preset-react',
              '@babel/preset-typescript'
            ]
          }
        }
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader']
      }
    ]
  },
  mode: 'production'
};

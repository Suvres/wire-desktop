@NonCPS
def parseJson(def text) {
  new groovy.json.JsonSlurperClassic().parseText(text)
}

node('master') {
  def production = params.PRODUCTION
  def custom = params.CUSTOM
  def NODE = tool name: 'node-v14.15.3', type: 'nodejs'
  def privateAPIResult = ''

  def jenkinsbot_secret = ''
  withCredentials([string(credentialsId: "${params.JENKINSBOT_SECRET}", variable: 'JENKINSBOT_SECRET')]) {
    jenkinsbot_secret = env.JENKINSBOT_SECRET
  }

  if (!production && !custom) {
    env.APP_ENV = 'internal'
  }

  stage('Checkout & Clean') {
    git branch: "${GIT_BRANCH}", url: 'https://github.com/wireapp/wire-desktop.git'
    sh returnStatus: true, script: 'rm -rf node_modules/ *.sig *.pkg'
  }

  def wireJson = readFile('electron/wire.json')
  def packageJson = readFile('package.json')
  def (major, minor) = parseJson(wireJson).version.tokenize('.')
  def version = "${major}.${minor}.${env.BUILD_NUMBER}"
  def electronVersion = parseJson(packageJson).devDependencies.electron
  currentBuild.displayName = version

  stage('Prepare build') {
    try {
      withCredentials([string(credentialsId: 'MACOS_KEYCHAIN_PASSWORD', variable: 'MACOS_KEYCHAIN_PASSWORD')]) {
        sh 'security unlock-keychain -p \"$MACOS_KEYCHAIN_PASSWORD\" /Users/jenkins/Library/Keychains/login.keychain'
      }
      withEnv(["PATH+NODE=${NODE}/bin"]) {
        sh 'node -v'
        sh 'npm -v'
        sh 'npm install -g yarn'
        sh 'yarn'
        sh 'yarn build:prepare'
      }
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🍏 **${JOB_NAME} ${version} build failed**\n${BUILD_URL}"
      throw e
    }
  }

  stage('Build') {
    try {
      withEnv(["PATH+NODE=${NODE}/bin"]) {
        if (production) {
          withCredentials([
            string(credentialsId: 'APPLE_EXPORT_COMPLIANCE_CODE', variable: 'APPLE_EXPORT_COMPLIANCE_CODE'),
            string(credentialsId: 'MACOS_NOTARIZE_EMAIL', variable: 'MACOS_NOTARIZE_APPLE_ID'),
            string(credentialsId: 'MACOS_NOTARIZE_PASSWORD', variable: 'MACOS_NOTARIZE_APPLE_PASSWORD'),
          ]) {
            sh 'yarn build:macos'
          }

          echo 'Checking for private Apple APIs in MAS build ...'
          privateAPIResult = sh script: 'bin/macos-check_private_apis.sh "wrap/dist/mas/Wire.app" "MAS"', returnStdout: true
          echo privateAPIResult

          echo 'Checking for private Apple APIs in DMG build ...'
          privateAPIResult = sh script: 'bin/macos-check_private_apis.sh "wrap/dist/mac/Wire.app" "DMG"', returnStdout: true
          echo privateAPIResult

          if (params.MACOS_ENABLE_NOTARIZATION) {
            echo 'Checking notarization in DMG build ...'
            notarizationResult = sh script: 'bin/macos-check_notarization.sh "wrap/dist/mac/Wire.app"', returnStdout: true
            echo notarizationResult
          }
        } else if (custom) {
          sh 'yarn build:macos'
        } else {
          // internal
          sh 'yarn build:macos:internal'

          echo 'Checking for private Apple APIs in DMG build ...'
          privateAPIResult = sh script: 'bin/macos-check_private_apis.sh "wrap/dist/mac/WireInternal.app" "DMG"', returnStdout: true
          echo privateAPIResult

          if (params.MACOS_ENABLE_NOTARIZATION) {
            echo 'Checking notarization in DMG build ...'
            notarizationResult = sh script: 'bin/macos-check_notarization.sh "wrap/dist/mac/WireInternal.app"', returnStdout: true
            echo notarizationResult
          }
        }
      }
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🍏 **${JOB_NAME} ${version} build failed**\n${BUILD_URL}"
      throw e
    }
  }

  if (production) {
    stage('Create SHA256 checksums') {
      withCredentials([file(credentialsId: 'D599C1AA126762B1.asc', variable: 'PGP_PRIVATE_KEY_FILE'), string(credentialsId: 'PGP_PASSPHRASE', variable: 'PGP_PASSPHRASE')]) {
        sh "cd wrap/dist && ../../bin/macos-checksums.sh ${version}"
      }
    }
  }

  stage('Archive build artifacts') {
    if (!production && !custom) {
      // Internal
      sh "ditto -c -k --sequesterRsrc --keepParent \"${WORKSPACE}/wrap/dist/WireInternal.app\" \"${WORKSPACE}/wrap/dist/WireInternal.zip\""
    }
    archiveArtifacts "wrap/dist/*.dmg,wrap/dist/*.asc,wrap/dist/*.pkg,wrap/dist/*.zip"
    sh returnStatus: true, script: 'rm -rf wrap/'
  }

  stage('Trigger smoke tests') {
    if (production) {
      try {
        build job: 'Wrapper_macOS_Smoke_Tests', parameters: [run(description: '', name: 'WRAPPER_BUILD', runId: "Wrapper_macOS_Production#${BUILD_ID}"), string(name: 'WEBAPP_ENV', value: 'https://wire-webapp-master.zinfra.io/')], wait: false
      } catch(e) {
        wireSend secret: "${jenkinsbot_secret}", message: "🍏 **${JOB_NAME} Unable to trigger smoke tests for ${version}**\n${BUILD_URL}"
        print e
      }
    }
  }

  wireSend secret: "${jenkinsbot_secret}", message: "🍏 **New build of ${JOB_NAME} ${version}**\n- Download: [Jenkins](${BUILD_URL})\n- Electron version: ${electronVersion}\n- Branch: [${GIT_BRANCH}](https://github.com/wireapp/wire-desktop/commits/${GIT_BRANCH})\n\n${privateAPIResult.trim()}\n\n${notarizationResult.trim()}"
}

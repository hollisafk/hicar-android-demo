"""Build both ARM libraries from the bundled OpenHarmony source subset."""
import os
from pathlib import Path
import platform
import shutil
import subprocess

root = Path(__file__).resolve().parent
ndk = Path(os.environ['ANDROID_NDK_ROOT']).resolve()
cmake = shutil.which('cmake')
if not cmake:
    raise SystemExit('Install CMake 3.18 or newer and put it on PATH')
host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}.get(platform.system())
if not host:
    raise SystemExit('Use macOS or Linux/WSL for this build script')
for abi, target in [('arm64-v8a', 'android-arm64'), ('armeabi-v7a', 'android-arm')]:
    out = root / ('android-' + abi)
    prefix = out / 'prefix'
    openssl = out / 'openssl'
    openssl.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env['ANDROID_NDK_ROOT'] = str(ndk)
    env['PATH'] = str(ndk / 'toolchains/llvm/prebuilt' / host / 'bin') + os.pathsep + env['PATH']
    # Do not embed workstation paths in public build artifacts.
    path_flag = '-ffile-prefix-map=' + str(root.parent) + '=.'
    env['CFLAGS'] = env.get('CFLAGS', '') + ' ' + path_flag
    jobs = os.environ.get('HICAR_BUILD_JOBS', '4')
    def run(command, cwd=None):
        subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True)
    run([root / 'deps/openssl/Configure', target, 'no-shared', 'no-tests', 'no-apps', '-D__ANDROID_API__=26', '--prefix=' + str(prefix)], openssl)
    run(['make', '-j' + jobs], openssl)
    run(['make', 'install_sw'], openssl)
    common = ['-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'), '-DANDROID_ABI=' + abi,
              '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
              '-DCMAKE_POLICY_VERSION_MINIMUM=3.5', '-DCMAKE_C_FLAGS=' + path_flag, '-DCMAKE_INSTALL_PREFIX=' + str(prefix)]
    for name, options in [('mbedtls', ['-DENABLE_TESTING=OFF', '-DENABLE_PROGRAMS=OFF']), ('cjson', ['-DENABLE_CJSON_TEST=OFF', '-DBUILD_SHARED_LIBS=OFF'])]:
        destination = out / name
        run([cmake, '-S', root / 'deps' / name, '-B', destination, *common, *options])
        run([cmake, '--build', destination, '-j' + jobs])
        run([cmake, '--install', destination])
    destination = out / 'auth'
    run([cmake, '-S', root / 'runtime', '-B', destination, *common, '-DSOURCE_ROOT=' + str(root / 'sources'),
         '-DOPENSSL_INCLUDE_DIR=' + str(prefix / 'include'), '-DOPENSSL_CRYPTO_LIBRARY=' + str(prefix / 'lib/libcrypto.a'),
         '-DCJSON_INCLUDE=' + str(prefix / 'include/cjson'), '-DCJSON_LIB=' + str(prefix / 'lib/libcjson.a'),
         '-DMBEDCRYPTO_LIB=' + str(prefix / 'lib/libmbedcrypto.a')])
    run([cmake, '--build', destination, '-j' + jobs])
    library = root.parent / 'app/native-libs/lib' / abi / 'libcarlink_hichain.so'
    library.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(destination / 'libcarlink_hichain.so', library)
    run([ndk / 'toolchains/llvm/prebuilt' / host / 'bin/llvm-strip', '--strip-debug', library])
    print(abi + ': copied library to app/native-libs')

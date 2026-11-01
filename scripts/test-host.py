"""Run bounded protocol tests on the host; does not connect to a phone."""
from pathlib import Path
import hashlib
import os
import subprocess

root = Path(__file__).resolve().parents[1]
java = Path(os.environ['JAVA_HOME']) / 'bin'
output = root / 'test-results/classes'
output.mkdir(parents=True, exist_ok=True)
jar = root / 'native-auth/deps/json-20240303.jar'
jar.parent.mkdir(parents=True, exist_ok=True)
if not jar.exists():
    subprocess.run(['curl', '-fL', '--retry', '2', '--max-time', '120', 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', '-o', str(jar)], check=True)
if hashlib.sha256(jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('JSON dependency SHA-256 mismatch')
subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-cp', str(jar), '-sourcepath', str(root / 'app/src') + os.pathsep + str(root / 'app/tests'), '-d', str(output), *map(str, sorted((root / 'app/tests').glob('*.java')))], check=True)
tests = [
    ('BeaconTest', []), ('PortTest', []), ('WireIdentityTest', []),
    ('WireTest', [str(root / 'native-auth/wire-vectors.txt'), str(root / 'test-results/wire.bin')]),
    ('NetworkTest', [str(root / 'native-auth/network-vectors.txt')]), ('NetworkFrequencyTest', []),
    ('DmsdpTest', [str(root / 'native-auth/dmsdp-vectors.txt')]), ('CipherTest', []),
    ('ControlTest', []), ('DeviceBusinessTest', []), ('DisplayGateTest', []),
    ('HiSightTest', []), ('HiSightTcpTest', ['--media-idle']), ('UiInputTest', []),
    ('AudioTest', []), ('MicrophoneTest', []), ('MicrophoneCapabilityTest', []),
    ('ReceiverStatusTest', []), ('ApScanTest', [])
]
for name, args in tests:
    print('Running ' + name, flush=True)
    subprocess.run([str(java / 'java'), '-cp', str(output) + os.pathsep + str(jar), 'local.hicarlab.' + name, *args], check=True, timeout=90)
print('All host tests passed. This does not prove device or car compatibility.')

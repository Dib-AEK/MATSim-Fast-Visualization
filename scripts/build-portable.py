"""Build a portable app image on Windows or Linux. Builder needs JDK 21+, Maven and Python 3."""
from pathlib import Path
import os
import platform
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
windows = platform.system() == 'Windows'
if platform.system() not in ('Windows', 'Linux'):
    raise SystemExit('This build supports Windows and Linux. Build on the target OS/architecture.')
suffix = '.exe' if windows else ''
java_home = os.environ.get('JAVA_HOME')
if not java_home:
    info = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
    java_home = re.search(r'java.home\s*=\s*(.+)', info.stderr).group(1).strip()
jdk = Path(java_home)
for tool in ('java', 'jar', 'jlink', 'jpackage'):
    if not (jdk / 'bin' / (tool + suffix)).is_file():
        raise SystemExit(f'JAVA_HOME must point to a full JDK 21+ with {tool}: {jdk}')
info = subprocess.run([str(jdk / 'bin' / ('java' + suffix)), '-version'], capture_output=True, text=True, check=True)
version = re.search(r'version "(\d+)', info.stderr)
if not version or int(version.group(1)) < 21:
    raise SystemExit('Use JDK 21 or newer (set JAVA_HOME).')
maven = shutil.which('mvn.cmd' if windows else 'mvn')
if not maven:
    raise SystemExit('Maven is required on the build machine, but not on recipients\' computers.')
env = os.environ.copy()
env['JAVA_HOME'] = str(jdk)
env['PATH'] = str(jdk / 'bin') + os.pathsep + env['PATH']

def run(args):
    print('Running:', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True, env=env)

# Unique output directories preserve previous builds and never delete workspace files.
(ROOT / 'target').mkdir(exist_ok=True)
build = Path(tempfile.mkdtemp(prefix='portable-', dir=ROOT / 'target'))
stage = build / 'input'
(stage / 'lib').mkdir(parents=True)
run([maven, '-q', '-DskipTests', 'compile', 'dependency:copy-dependencies', '-DincludeScope=runtime', f'-DoutputDirectory={stage / "lib"}'])
manifest = build / 'MANIFEST.MF'
# Manifest continuation lines must stay below the JAR byte limit.
classpath = 'Class-Path: ' + ' '.join('lib/' + p.name for p in sorted((stage / 'lib').glob('*.jar')))
wrapped = []
while classpath:
    wrapped.append(classpath[:70])
    classpath = ' ' + classpath[70:] if len(classpath) > 70 else ''
manifest.write_text('Manifest-Version: 1.0\nMain-Class: com.matsim.viz.launcher.PortableLauncher\n' + '\n'.join(wrapped) + '\n\n', encoding='utf-8')
run([jdk / 'bin' / ('jar' + suffix), '--create', '--file', stage / 'MATSimViz.jar', '--manifest', manifest, '-C', ROOT / 'target/classes', '.'])
(stage / 'config').mkdir()
shutil.copy2(ROOT / 'config/app.properties', stage / 'config/app.properties')
shutil.copy2(ROOT / 'README.md', stage / 'README.md')
for name in ('LICENSE', 'LICENSE.md', 'NOTICE'):
    if (ROOT / name).is_file():
        shutil.copy2(ROOT / name, stage / name)
# Keep java/java.exe: the bootstrap starts the viewer in a child JVM for restart/retry support.
run([jdk / 'bin' / ('jlink' + suffix), '--add-modules', 'ALL-MODULE-PATH', '--output', build / 'runtime', '--strip-debug', '--no-header-files', '--no-man-pages'])
run([jdk / 'bin' / ('jpackage' + suffix), '--type', 'app-image', '--name', 'MATSimViz', '--app-version', '1.0.0', '--input', stage, '--main-jar', 'MATSimViz.jar', '--main-class', 'com.matsim.viz.launcher.PortableLauncher', '--runtime-image', build / 'runtime', '--dest', build / 'distribution', '--vendor', 'MATSim Fast Visualization'])
image = build / 'distribution/MATSimViz'
(image / 'START-HERE.txt').write_text('MATSim Fast Visualization\n\nWindows: double-click MATSimViz.exe.\nLinux: run bin/MATSimViz (desktop Linux with GTK/display required).\nKeep and share this entire folder, including app/lib and the bundled runtime.\nNo Java, Maven or Python is needed to run it.\nChoose your MATSim config and review detected files, or choose an existing cache.\nSettings, logs and the default cache are stored under ~/.matsim-viz.\nThe original simulation files are not included.\n', encoding='utf-8')
print(f'\nPortable application ready: {image}\nShare this entire folder. Build separately for each OS and CPU architecture.')

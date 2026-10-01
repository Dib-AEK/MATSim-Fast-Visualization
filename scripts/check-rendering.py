"""Compile and run renderer, video, CRS and MATSim integration checks (JDK 21+)."""
from pathlib import Path
import os
import subprocess

os.chdir(Path(__file__).resolve().parent.parent)
classpath_file = Path('target/test-classpath.txt')
if not classpath_file.exists():
    raise SystemExit('First run: mvn dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"')
dependencies = classpath_file.read_text().strip()
output = Path('target/visual-check').resolve()
output.mkdir(parents=True, exist_ok=True)
sources = list(Path('src/main/java').rglob('*.java')) + list(Path('src/test/java').rglob('*.java'))

def argfile(path, arguments):
    path.write_text('\n'.join('"' + str(arg).replace('\\', '/') + '"' for arg in arguments))
    return '@' + str(path)

subprocess.run(['javac', argfile(Path('target/render-check-compile.args'),
    ['-proc:none', '-cp', dependencies, '-d', output, *sources])], check=True)
for check in ('VolumeWidthCheck', 'PlaybackSeekCheck', 'VehicleMotionCheck', 'VehicleSizeCheck', 'PublicTransportModesCheck', 'DefaultsCheck', 'DetailedGeometryCheck', 'NetworkEditorCheck', 'TransitEditorCheck', 'HeatmapTransitionCheck', 'PanOpacityCheck', 'RoadTaperCheck', 'ZoomDetailCheck', 'CarriagewayRenderingCheck', 'RecordingBufferCheck', 'RecordingQualityCheck', 'MapBackgroundCheck', 'MatsimIntegrationCheck', 'BusEventsCheck'):
    subprocess.run(['java', argfile(Path('target/render-check-run.args'),
        ['-Djava.awt.headless=true', '-cp', str(output) + os.pathsep + dependencies,
         'com.matsim.viz.ui.' + check])], check=True)

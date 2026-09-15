#!/usr/bin/env bash
# Test the selector's behavior and its real workflow wiring. In particular,
# skipping Play must not suppress the GitHub release or invent a Play action.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

python3 - "$repo_root" <<'PY'
import os
import pathlib
import subprocess
import sys
import tempfile
import yaml

root = pathlib.Path(sys.argv[1])
script = root / '.github/scripts/select-release-action.sh'
checked = 0

def check(condition, message):
    global checked
    checked += 1
    assert condition, message

def select(publish, rung, code, lane, exception='', status='Normal release status'):
    return subprocess.run(['bash', str(script), publish, rung, str(code), lane, status, exception],
                          capture_output=True, text=True)

def outputs(result):
    check(result.returncode == 0, result.stderr)
    return dict(line.split('=', 1) for line in result.stdout.splitlines())

lanes = {'dev': 'distribute_dev', 'beta': 'promote_beta', 'production': 'promote_production'}
for rung, lane in lanes.items():
    exception = '' if rung == 'dev' else '1961'
    for code in (1960, 1961, 1962, 1970):
        result = outputs(select('true', rung, code, lane, exception))
        skipped = rung != 'dev' and code == 1961
        check(result['lane'] == ('build_release_candidates' if skipped else lane), f'{rung}/{code}: wrong lane')
        check(result['requires_play'] == str(not skipped).lower(), f'{rung}/{code}: wrong Play requirement')
        check(result['play_already_published'] == str(skipped).lower(), f'{rung}/{code}: wrong exception flag')
        expected_status = 'Already live on Play — GitHub release next' if skipped else 'Normal release status'
        check(result['caption_status'] == expected_status, f'{rung}/{code}: misleading caption status')

        no_publish = outputs(select('false', rung, code, lane, exception))
        check(no_publish['lane'] == 'build_release_candidates', f'{rung}/{code}: nonpublisher selected a Play lane')
        check(no_publish['requires_play'] == 'false', f'{rung}/{code}: nonpublisher requested Play credentials')
        check(no_publish['play_already_published'] == 'false', f'{rung}/{code}: nonpublisher claims a release')

    default = outputs(select('true', rung, 1961, lane))
    check(default['lane'] == lane and default['requires_play'] == 'true', f'{rung}: default routing changed')

for args in (
    ('true', 'dev', 1961, 'distribute_dev', '1961'),
    ('false', 'dev', 1961, 'distribute_dev', '1961'),
    ('true', 'beta', 1961, 'promote_beta', '1961x'),
    ('true', 'production', 1961, 'promote_production', ' 1961'),
    ('true', 'production', '1961x', 'promote_production', '1961'),
    ('true', 'beta', 1961, 'distribute_dev', '1961'),
    ('true', 'unknown', 1961, 'promote_production', '1961'),
    ('yes', 'beta', 1961, 'promote_beta', '1961'),
):
    check(select(*args).returncode != 0, f'invalid configuration accepted: {args}')
check(select('true', 'beta', 1961, 'promote_beta', '1961', 'line one\nline two').returncode != 0,
      'multiline caption could inject workflow outputs')
print('  ok: selector matrix covers exact match, adjacent/future codes, defaults, nonpublishing, and invalid inputs')

workflows = root / '.github/workflows'
rung_doc = yaml.safe_load((workflows / 'release-rung.yml').read_text())
# PyYAML's YAML 1.1 parser treats the bare workflow key `on` as True.
inputs = rung_doc.get('on', rung_doc.get(True))['workflow_call']['inputs']
policy_input = inputs['play_already_published_version_code']
check(policy_input['type'] == 'string' and policy_input['default'] == '' and not policy_input['required'],
      'the version exception must default to disabled')

for rung, filename in {'dev': '1-dev-publish.yml', 'beta': '2-master-promote.yml', 'production': '3-production-promote.yml'}.items():
    caller = yaml.safe_load((workflows / filename).read_text())
    config = next(iter(caller['jobs'].values()))['with']
    check(config.get('play_already_published_version_code', '') == ('' if rung == 'dev' else '1961'),
          f'{filename}: exception is not limited to the intended code and rungs')

steps = rung_doc['jobs']['rung']['steps']
by_name = {step.get('name'): step for step in steps}
selector = by_name['Select release action']
check(selector['id'] == 'release_action', 'selector output id changed')
for key, expression in {
    'PUBLISH': '${{ steps.gate.outputs.publish }}',
    'RUNG': '${{ inputs.rung }}',
    'CODE': '${{ steps.ver.outputs.code }}',
    'LANE': '${{ inputs.fastlane_lane }}',
    'CAPTION_STATUS': '${{ inputs.caption_status }}',
    'ALREADY_PUBLISHED_CODE': '${{ inputs.play_already_published_version_code }}',
}.items():
    check(selector['env'][key] == expression, f'selector miswired {key}')
check('select-release-action.sh' in selector['run'] and '"$GITHUB_OUTPUT"' in selector['run'],
      'workflow does not publish the real selector outputs')
check(steps.index(by_name['Decide whether this run publishes']) < steps.index(selector)
      < steps.index(by_name['Check release-notes budget']), 'selector must run after publishing gate and before budget check')

for name in ('Check release-notes budget', 'Send APK to Telegram'):
    check(by_name[name]['env']['CAPTION_STATUS'] == '${{ steps.release_action.outputs.caption_status }}',
          f'{name} ignores the effective caption status')
check(by_name['Decode Google Play Service Account']['if'] == "steps.release_action.outputs.requires_play == 'true'",
      'Play credentials are decoded for a build-only release')
run_step = by_name['Run Fastlane']
check(run_step['env']['RELEASE_LANE'] == '${{ steps.release_action.outputs.lane }}', 'Fastlane ignores selected lane')
check(run_step['env']['PLAY_ALREADY_PUBLISHED'] == '${{ steps.release_action.outputs.play_already_published }}',
      'Fastlane ignores the manual release state')
release = by_name['Create GitHub Release']
check(release['if'] == "${{ !cancelled() && steps.gate.outputs.publish == 'true' && steps.prep_notes.outcome == 'success' }}",
      'GitHub publishing must remain independent of whether Play runs')
check(release['with']['target_commitish'] == '${{ github.sha }}', 'release tag no longer pins the built commit')
check('foss-release.apk' in release['with']['files'] and 'store-release.apk' in release['with']['files'],
      'both signed APKs must remain attached')

# Execute the actual Run Fastlane shell block with a local recorder in place
# of bundle. This catches hardcoded promotion commands beyond selector output
# assertions and proves track.txt is changed only for the manual release.
with tempfile.TemporaryDirectory(prefix='thor-release-action-') as directory:
    fixture = pathlib.Path(directory)
    binary = fixture / 'bundle'
    binary.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > invocation.txt\nprintf "" > track.txt\n')
    binary.chmod(0o755)
    for rung in ('beta', 'production'):
        for code in (1961, 1970):
            selected = outputs(select('true', rung, code, lanes[rung], '1961'))
            env = {**os.environ, 'PATH': f'{directory}{os.pathsep}{os.environ["PATH"]}',
                   'RELEASE_LANE': selected['lane'], 'PLAY_ALREADY_PUBLISHED': selected['play_already_published']}
            subprocess.run(['bash', '-euo', 'pipefail', '-c', run_step['run']], cwd=directory, env=env, check=True)
            invoked = (fixture / 'invocation.txt').read_text().splitlines()
            check(invoked == ['exec', 'fastlane', 'android', selected['lane']], f'{rung}/{code}: workflow ran the wrong command')
            check((fixture / 'track.txt').read_text() == ('production' if code == 1961 else ''),
                  f'{rung}/{code}: workflow misreported the Play track')
print('  ok: workflow bypasses Play credentials/actions while keeping signed APK releases and truthful captions')
print(f'  {checked} assertion(s)')
PY

# Execute the selected lane in the real Fastfile. Only the fastlane DSL and
# build process are stubbed; either Play action is an immediate test failure.
ruby - "$repo_root" <<'RUBY'
require 'tmpdir'
require 'fileutils'

module UI
  def self.message(*); end
  def self.success(*); end
  def self.important(*); end
  def self.user_error!(message)
    raise message
  end
end

class BuildOnlyFastfile
  attr_reader :build_tasks

  def initialize(path)
    @lanes = {}
    instance_eval(File.read(path), path)
  end

  def default_platform(*); end
  def desc(*); end
  def platform(*)
    yield
  end
  def lane(name, &block)
    @lanes[name] = block
  end
  def run_build
    @lanes.fetch(:build_release_candidates).call({})
  end
  def sh(*)
    code = ThorRelease.version_code_from('gradle.properties')
    ThorRelease.version_name_for(code)
  end
  def gradle(**options)
    @build_tasks = options.fetch(:task)
  end
  def upload_to_play_store(*)
    raise 'Build-only lane attempted to write Google Play'
  end
  def google_play_track_version_codes(*)
    raise 'Build-only lane attempted to read Google Play'
  end
end

assertions = 0
[1961, 1970].each do |code|
  Dir.mktmpdir('thor-build-only-') do |root|
    File.write(File.join(root, 'gradle.properties'), "versionCode=#{code}\n")
    FileUtils.mkdir_p(File.join(root, 'fastlane'))
    fastfile = BuildOnlyFastfile.new(File.join(ARGV.fetch(0), 'fastlane', 'Fastfile'))
    Dir.chdir(File.join(root, 'fastlane')) { fastfile.run_build }
    raise 'Wrong build tasks or unexpected bundle build' unless fastfile.build_tasks == 'clean copyStoreReleaseApk copyFossReleaseApk'
    raise 'Missing version code artifact' unless File.read(File.join(root, 'version_code.txt')) == code.to_s
    raise 'Missing version name artifact' unless File.read(File.join(root, 'version_name.txt')) == ThorRelease.version_name_for(code)
    raise 'Build-only lane claimed a Play track' unless File.read(File.join(root, 'track.txt')).empty?
    assertions += 5 # four output checks and completion without either forbidden Play call
  end
end
puts '  ok: real build_release_candidates lane builds both APKs and version artifacts without reading or writing Play'
puts "  #{assertions} assertion(s)"
RUBY

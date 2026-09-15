# frozen_string_literal: true

require 'minitest/autorun'
require 'tmpdir'
require 'fileutils'

# Evaluate the real Fastfile with only the fastlane DSL and external actions
# stubbed. The file-copy code runs unchanged, without a build or Play upload.
module UI
  def self.message(*); end
  def self.success(*); end
  def self.important(*); end
  def self.user_error!(message)
    raise ArgumentError, message
  end
end

module SharedValues
  GRADLE_AAB_OUTPUT_PATH = :aab
end

class ChangelogFastfile
  attr_reader :uploads

  def initialize
    @uploads = []
    path = File.expand_path('../Fastfile', __dir__)
    instance_eval(File.read(path), path)
  end

  def default_platform(*); end
  def platform(*)
    yield
  end
  def desc(*); end
  def lane(*); end
  def gradle(*); end
  def sh(*)
    '1.96.1'
  end
  def lane_context
    { SharedValues::GRADLE_AAB_OUTPUT_PATH => 'fixture.aab' }
  end
  def upload_to_play_store(**options)
    @uploads << options
  end
end

class TestFastfileChangelogs < Minitest::Test
  NOTES = "• 🛠 First bullet with \"quotes\".\n\n• 🎨 Second bullet keeps literal \\n.\n\n• 🚀 Final bullet.\n".freeze

  def setup
    @root = Dir.mktmpdir('thor-changelogs')
    @notes = File.join(@root, 'release-notes', 'v1.96.1', 'playstore.txt')
    FileUtils.mkdir_p(File.dirname(@notes))
    File.write(@notes, NOTES)
    File.write(File.join(@root, 'gradle.properties'), "versionCode=1961\n")
    %w[en-US hi-IN].each do |locale|
      FileUtils.mkdir_p(File.dirname(changelog(locale)))
    end
    @fastfile = ChangelogFastfile.new
  end

  def teardown
    FileUtils.remove_entry(@root)
  end

  def changelog(locale)
    File.join(@root, 'fastlane', 'metadata', 'android', locale, 'changelogs', '1961.txt')
  end

  def copy_notes(**options)
    @fastfile.copy_playstore_notes(project_root: @root, version_name: '1.96.1', version_code: 1961, **options)
  end

  def dev_upload
    Dir.chdir(File.join(@root, 'fastlane')) do
      @fastfile.prepare_release_artifacts(upload_to_store: true, track: 'alpha')
    end
  end

  def test_dev_upload_copies_every_line_and_blank_line_for_every_locale
    dev_upload

    %w[en-US hi-IN].each do |locale|
      assert_equal NOTES.b, File.binread(changelog(locale))
    end
    refute @fastfile.uploads.first.fetch(:skip_upload_changelogs)
  end

  def test_overwrites_truncated_english_and_preserves_translation
    translation = "• पहला बदलाव।\n\n• दूसरा बदलाव।\n"
    File.write(changelog('en-US'), NOTES.lines.first)
    File.write(changelog('hi-IN'), translation)

    assert copy_notes
    assert_equal NOTES.b, File.binread(changelog('en-US'))
    assert_equal translation.b, File.binread(changelog('hi-IN'))
  end

  def test_empty_locale_receives_complete_english_fallback
    File.write(changelog('hi-IN'), " \n")

    copy_notes

    assert_equal NOTES.b, File.binread(changelog('hi-IN'))
  end

  def test_legacy_notes_directory_preserves_complete_contents
    FileUtils.mv(File.dirname(@notes), File.join(@root, 'release-notes', '1.96.1'))

    copy_notes

    assert_equal NOTES.b, File.binread(changelog('en-US'))
  end

  def test_absent_notes_remain_optional_for_dev
    FileUtils.rm(@notes)

    dev_upload

    assert @fastfile.uploads.first.fetch(:skip_upload_changelogs)
  end

  def test_absent_notes_are_required_for_production
    FileUtils.rm(@notes)

    assert_raises(ArgumentError) { copy_notes }
  end

  def test_empty_curated_notes_stop_before_upload
    File.write(@notes, " \n\n")

    assert_raises(ArgumentError) { dev_upload }
    assert_empty @fastfile.uploads
  end

  def test_copy_failure_stops_before_upload
    File.symlink(File.join(@root, 'missing-directory', 'notes.txt'), changelog('en-US'))

    assert_raises(SystemCallError) { dev_upload }
    assert_empty @fastfile.uploads
  end
end

# frozen_string_literal: true

require 'minitest/autorun'
require 'tmpdir'
require_relative '../lib/thor_release'

class TestVersionArithmetic < Minitest::Test
  # versionName is derived as code/1000 . code%1000/10 . code%10 - the same
  # arithmetic as app/build.gradle.kts and check-shizu-manifest.sh. Three
  # independent implementations of one rule; this is the one with tests.
  def test_stable_code
    assert_equal '1.94.0', ThorRelease.version_name_for(1940)
  end

  def test_patch_code
    assert_equal '1.93.3', ThorRelease.version_name_for(1933)
  end

  def test_two_digit_minor
    assert_equal '1.90.8', ThorRelease.version_name_for(1908)
  end

  def test_minor_boundary
    assert_equal '1.9.9', ThorRelease.version_name_for(1099)
  end
end

class TestVersionCodeParsing < Minitest::Test
  def with_properties(contents)
    Dir.mktmpdir do |dir|
      path = File.join(dir, 'gradle.properties')
      File.write(path, contents)
      yield path
    end
  end

  def test_reads_the_code
    with_properties("org.gradle.jvmargs=-Xmx4g\nversionCode=1940\n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  # An unanchored match also finds initialVersionCode=1921, which is the bug
  # that made the old release-manager workflow unusable.
  def test_ignores_other_keys_containing_versioncode
    with_properties("initialVersionCode=1921\nversionCode=1940\n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  def test_tolerates_surrounding_whitespace
    with_properties("  versionCode = 1940  \n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  def test_ignores_a_commented_out_code
    with_properties("#versionCode=1234\nversionCode=1940\n") do |p|
      assert_equal 1940, ThorRelease.version_code_from(p)
    end
  end

  def test_raises_when_absent
    with_properties("org.gradle.jvmargs=-Xmx4g\n") do |p|
      err = assert_raises(ThorRelease::Error) { ThorRelease.version_code_from(p) }
      assert_match(/versionCode/, err.message)
    end
  end

  def test_raises_when_file_missing
    assert_raises(ThorRelease::Error) { ThorRelease.version_code_from('/nope/gradle.properties') }
  end
end

class TestTrackGuards < Minitest::Test
  def test_alpha_is_uploadable
    assert_equal 'alpha', ThorRelease.validate_upload_track!('alpha')
  end

  def test_internal_is_uploadable
    assert_equal 'internal', ThorRelease.validate_upload_track!('internal')
  end

  # The whole point of the ladder: exactly one branch uploads. beta and
  # production are reached by promotion only, so an upload naming them is a
  # bug, not a shortcut.
  def test_beta_is_not_uploadable
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!('beta') }
  end

  def test_production_is_not_uploadable
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!('production') }
  end

  # upload_to_play_store CREATES a track by an unknown name rather than
  # failing, so a typo must be caught here.
  def test_typo_is_rejected
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!('alhpa') }
  end

  def test_strips_whitespace
    assert_equal 'alpha', ThorRelease.validate_upload_track!("  alpha\n")
  end

  def test_nil_is_rejected
    assert_raises(ThorRelease::Error) { ThorRelease.validate_upload_track!(nil) }
  end
end

class TestPromotionEdges < Minitest::Test
  def test_beta_comes_from_alpha
    assert_equal 'alpha', ThorRelease.source_track_for('beta')
  end

  def test_production_comes_from_beta
    assert_equal 'beta', ThorRelease.source_track_for('production')
  end

  # A rung may only promote from the track directly below it. Skipping a rung
  # must be a red build, not a silent shortcut to production.
  def test_production_cannot_come_from_alpha
    refute_equal 'alpha', ThorRelease.source_track_for('production')
  end

  def test_alpha_is_not_a_promotion_destination
    assert_raises(ThorRelease::Error) { ThorRelease.source_track_for('alpha') }
  end

  def test_unknown_destination_is_rejected
    assert_raises(ThorRelease::Error) { ThorRelease.source_track_for('nonsense') }
  end
end

class TestCodePresenceAssertion < Minitest::Test
  def test_passes_when_present
    assert ThorRelease.assert_code_present!(code: 1940, track: 'alpha', codes_in_track: [1933, 1940])
  end

  def test_raises_when_absent
    err = assert_raises(ThorRelease::Error) do
      ThorRelease.assert_code_present!(code: 1941, track: 'alpha', codes_in_track: [1933, 1940])
    end
    assert_match(/1941/, err.message)
    assert_match(/alpha/, err.message)
  end

  # google_play_track_version_codes returns whatever the API gave, and an
  # empty array means "nothing in that track" - never "assume it is fine".
  def test_raises_on_empty_track
    assert_raises(ThorRelease::Error) do
      ThorRelease.assert_code_present!(code: 1940, track: 'beta', codes_in_track: [])
    end
  end

  # The API returns integers, but a code read from a file or an env var is a
  # string. Comparing them without coercion silently never matches.
  def test_coerces_string_codes
    assert ThorRelease.assert_code_present!(code: '1940', track: 'alpha', codes_in_track: ['1940'])
  end
end

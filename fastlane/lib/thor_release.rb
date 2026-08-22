# frozen_string_literal: true

# Pure logic for Thor's three-rung release ladder.
#
# Deliberately free of any fastlane dependency so it can be unit-tested with
# plain minitest: `ruby fastlane/test/test_thor_release.rb`. Anything here that
# starts needing a fastlane action belongs in the Fastfile instead.
#
# The ladder: dev uploads to alpha, master promotes alpha -> beta, production
# promotes beta -> production. Exactly one branch ever uploads, which is what
# makes Play's per-app (not per-track) version-code uniqueness a non-issue.
#
# The dev rung then MIRRORS its upload onto internal - the same release object
# on a second track, with no second upload. That leaves the "exactly one branch
# uploads" invariant untouched: mirroring moves no bytes.
module ThorRelease
  class Error < StandardError; end

  # Tracks an artifact may be UPLOADED to. beta and production are absent on
  # purpose - they are reached by promotion only. upload_to_play_store creates
  # a track by an unrecognised name rather than failing, so this list is the
  # only thing standing between a typo and a phantom track.
  UPLOAD_TRACKS = %w[internal alpha].freeze

  # destination => the track a build must already be in to be promoted there.
  # A rung may only promote from the track directly below it, so skipping a
  # rung fails the build instead of shipping an unreviewed build to users.
  PROMOTION_EDGES = {
    'beta' => 'alpha',
    'production' => 'beta'
  }.freeze

  # uploaded track => the further tracks that same version code is assigned to
  # once the upload has succeeded, without uploading it again.
  #
  # Deliberately NOT an entry in PROMOTION_EDGES. That map encodes the ladder's
  # one-rung-at-a-time invariant, and internal sits below alpha, so an
  # 'internal' => 'alpha' edge there would mean source_track_for('internal')
  # starts answering - teaching the promote lanes a downward edge in the map
  # whose whole purpose is to forbid unreviewed jumps. Mirroring is a different
  # operation with a different guarantee, so it gets its own table.
  #
  # Every target must also be an UPLOAD_TRACK: the mirror is performed with
  # track_promote_to, which creates a track by an unrecognised name just as
  # readily as an upload does.
  MIRROR_TRACKS = {
    'alpha' => %w[internal].freeze
  }.freeze

  def self.validate_upload_track!(track)
    normalised = track.to_s.strip
    unless UPLOAD_TRACKS.include?(normalised)
      raise Error, "upload track must be one of #{UPLOAD_TRACKS.join(', ')} - got #{track.inspect}. " \
                   'beta and production are promotion-only: exactly one branch uploads to Play.'
    end
    normalised
  end

  # The extra tracks an upload to `track` is mirrored onto. An unmirrored track
  # answers [] rather than raising: most tracks have no mirror, and "no mirror"
  # is a normal answer, not a misconfiguration.
  def self.mirror_tracks_for(track)
    normalised = track.to_s.strip
    targets = Array(MIRROR_TRACKS[normalised])

    targets.each do |target|
      if target == normalised
        raise Error, "#{normalised} is listed as a mirror of itself - a mirror must name a different track."
      end

      unless UPLOAD_TRACKS.include?(target)
        raise Error, "mirror target #{target.inspect} for #{normalised.inspect} is not in " \
                     "UPLOAD_TRACKS (#{UPLOAD_TRACKS.join(', ')}). Mirroring uses track_promote_to, " \
                     'which would create a phantom track by that name rather than fail.'
      end
    end

    targets
  end

  def self.source_track_for(destination)
    normalised = destination.to_s.strip
    PROMOTION_EDGES.fetch(normalised) do
      raise Error, "no promotion edge into #{destination.inspect}. " \
                   "Known destinations: #{PROMOTION_EDGES.keys.join(', ')}."
    end
  end

  # Anchored on purpose: an unanchored match also finds initialVersionCode,
  # which fed two lines into arithmetic and made the old release-manager
  # workflow unusable.
  VERSION_CODE_LINE = /^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$/.freeze

  def self.version_code_from(properties_path)
    unless File.file?(properties_path)
      raise Error, "gradle.properties not found at #{properties_path}"
    end

    File.readlines(properties_path).each do |line|
      next if line.lstrip.start_with?('#')

      match = VERSION_CODE_LINE.match(line)
      return match[1].to_i if match
    end

    raise Error, "no versionCode assignment found in #{properties_path}"
  end

  # Mirrors app/build.gradle.kts and .github/scripts/check-shizu-manifest.sh:
  # 1940 -> 1.94.0, 1933 -> 1.93.3. versionName is never stored, only derived.
  def self.version_name_for(code)
    n = code.to_i
    "#{n / 1000}.#{(n % 1000) / 10}.#{n % 10}"
  end

  # The invariant the whole ladder rests on: a rung may only promote a version
  # code that is already in the track below it. An empty track means "nothing
  # there", never "assume it is fine".
  def self.assert_code_present!(code:, track:, codes_in_track:)
    wanted = code.to_i
    present = Array(codes_in_track).map(&:to_i)

    unless present.include?(wanted)
      raise Error, "versionCode #{wanted} is not in the #{track} track " \
                   "(found: #{present.empty? ? 'nothing' : present.sort.join(', ')}). " \
                   'A rung may only promote a build the rung below it already published - ' \
                   'check that the lower rung actually ran.'
    end
    true
  end
end

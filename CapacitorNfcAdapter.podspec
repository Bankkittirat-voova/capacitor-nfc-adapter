require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name            = 'CapacitorNfcAdapter'
  s.version         = package['version']
  s.summary         = package['description']
  s.license         = package['license']
  s.homepage        = package['repository']['url']
  s.author          = package['author']
  s.source          = { :git => package['repository']['url'], :tag => s.version.to_s }

  # Core engine (ios/Sources) + Capacitor bridge (ios/Plugin); tests excluded.
  s.source_files    = 'ios/**/*.{swift,h,m,mm}'
  s.exclude_files   = 'ios/Tests/**/*'

  s.ios.deployment_target = '12.0'
  s.frameworks      = 'CoreNFC'
  s.dependency 'Capacitor'
  s.swift_version   = '5.1'
end

#!/bin/bash
set -e

if [ "$TRAVIS_BRANCH" == "master" -a "$TRAVIS_PULL_REQUEST" == "false" ]; then
  openssl aes-256-cbc -K $encrypted_fa45534951b5_key -iv $encrypted_fa45534951b5_iv -in travis/secrets.tar.enc -out travis/secrets.tar -d
  tar xv -C travis -f travis/secrets.tar
  if [ -z "$TRAVIS_TAG" ]; then
     # Publish a snapshot
     ./sbt "+publish"
  else
     # Publish a release version
     RELEASE=true ./sbt "; +publishSigned; sonatypeReleaseAll"
  fi
else
  echo "This not a master branch commit. Skipping the release step"
fi

#! /usr/bin/env bash

set -eou pipefail

echo "What is your organization/GitHub username? (e.g. 'camsaul')"
read ORG

echo "What is your project name? (e.g. 'toucan')"
read PROJECT

echo "Generating project..."

perl -pi -e "s/ORG/$ORG/g" project.clj .circleci/config.yml README.md .github/CODEOWNERS
perl -pi -e "s/PROJECT/$PROJECT/g" project.clj .circleci/config.yml README.md

echo "Resetting Git history..."

rm -rf .git
git init

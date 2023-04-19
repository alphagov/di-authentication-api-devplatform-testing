#!/usr/bin/env bash

set -eu

echo Hello World
cat <<EOF > "$TEST_REPORT_ABSOLUTE_DI/result.xml"

exit 0
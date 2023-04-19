#!/usr/bin/env bash

set -eu

echo Hello World
cat <<EOF > "$TEST_REPORT_DIR/result.json"
  [
    {
      "uri": "test.sh"
    }
  ]

exit 0
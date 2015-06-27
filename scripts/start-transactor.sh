#! /usr/bin/env bash

abspath() {
  # $1 : relative filename
  echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
}

PROPS=$(abspath `dirname "$0"`/../config/transactor.properties)
pushd ${DATOMIC_HOME:=~/work/datomic-free-0.9.5173}
bin/transactor $PROPS

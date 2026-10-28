"""Produce a fixed synthetic receipt with the pinned KEYRING Gateway method.

No command is sent, authorized, or executed. The caller verifies the source revision.
"""
import json
import sys
from pathlib import Path
from types import SimpleNamespace

source = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(source / 'keyring/raspberry-pi-gateway/src'))
from bubble_gateway.service import Gateway

gateway = Gateway.__new__(Gateway)
gateway.config = SimpleNamespace(gateway_id='app-isolated')
command = {'deviceId': 'synthetic-keyring', 'commandId': 'synthetic-command-1',
           'ownershipEpoch': 1, 'grantRevision': 1}
receipt = gateway._receipt(command, '0' * 64, 'INDETERMINATE', 'INVALID_RECEIPT',
                           'Synthetic test; no device command was sent')
print(json.dumps(receipt, separators=(',', ':')))

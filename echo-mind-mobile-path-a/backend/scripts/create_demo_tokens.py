from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.auth import create_access_token

TENANT = "t_demo"
for role, subject in [
    ("user", "u_demo"),
    ("on_call", "staff_oncall_01"),
    ("professional", "professional_01"),
    ("auditor", "auditor_01"),
    ("admin", "admin_01"),
]:
    print(f"{role:12s} {create_access_token(subject, TENANT, role, minutes=1440)}")

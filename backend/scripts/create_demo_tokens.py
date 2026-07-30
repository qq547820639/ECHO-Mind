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
    ("quality_reviewer", "quality_reviewer_01"),
    ("security_auditor", "security_auditor_01"),
    ("vendor_support", "vendor_support_01"),
]:
    print(f"{role:12s} {create_access_token(subject, TENANT, role, minutes=1440)}")

# 高危证据访问（escalation 详情/证据摘要）需要带 step-up 声明的 token。
print(f"{'on_call+step_up':12s} {create_access_token('staff_oncall_01', TENANT, 'on_call', minutes=15, step_up=True)}")

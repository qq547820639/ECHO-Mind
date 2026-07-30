import json, sys
from collections import Counter, defaultdict
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"backend"))
from app.services.safety import evaluate_text, RULE_PACK_VERSION
rows=[json.loads(x) for x in (ROOT/"safety-eval/red_team_corpus.v1.jsonl").read_text(encoding="utf-8").splitlines() if x]
conf=Counter(); bycat=defaultdict(lambda:Counter(total=0,correct=0))
for row in rows:
 got=evaluate_text(row["text"]).severity
 conf[(row["expected"],got)]+=1
 bycat[row["category"]]["total"]+=1
 bycat[row["category"]]["correct"]+=got==row["expected"]
report={
 "rule_pack_version":RULE_PACK_VERSION,
 "corpus":"synthetic red-team corpus; not clinical validation",
 "total":len(rows),
 "correct":sum(v for (e,g),v in conf.items() if e==g),
 "accuracy":sum(v for (e,g),v in conf.items() if e==g)/len(rows),
 "by_category":{k:dict(v,accuracy=v["correct"]/v["total"]) for k,v in bycat.items()},
 "confusion":{f"{e}->{g}":v for (e,g),v in sorted(conf.items())},
}
(ROOT/"safety-eval/evaluation_report.v1.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(0 if report["correct"]==report["total"] else 2)

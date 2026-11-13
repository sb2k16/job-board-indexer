#!/bin/zsh
# Usage: run_batch.sh batchNN  -- runs probes/batchNN.json on the probe instance, stores output in samples/batchNN
set -e; B=${1:?batch name required}; D=${0:A:h}; export AWS_PROFILE=jbi AWS_REGION=us-east-1
I=${JBI_INSTANCE:-i-0e1ccc223717b28c1}; BK=jbi-phase0-803817393850
PB=$(base64 < $D/probe.py | tr -d '\n'); TB=$(base64 < $D/probes/$B.json | tr -d '\n')
python3 -I - "$PB" "$TB" "$B" "$BK" > $D/.params.json <<'E'
import json,sys
p,t,b,k=sys.argv[1:]
print(json.dumps({"commands":[
 "set -e","W=$(mktemp -d)","cd $W",f"echo {p} | base64 -d > p.py",f"echo {t} | base64 -d > t.json",
 "python3 -I p.py t.json out","tar czf out.tgz -C out .",f"aws s3 cp out.tgz s3://{k}/phase0/{b}.tgz --only-show-errors --region us-east-1","echo DONE; cd /; rm -rf $W"],"executionTimeout":["1500"]}))
E
C=$(aws ssm send-command --instance-ids $I --document-name AWS-RunShellScript --timeout-seconds 600 --parameters file://$D/.params.json --query Command.CommandId --output text)
aws ssm wait command-executed --command-id $C --instance-id $I 2>/dev/null || true
for i in {1..60}; do S=$(aws ssm get-command-invocation --command-id $C --instance-id $I --query Status --output text 2>/dev/null || echo Pending); [[ $S == InProgress || $S == Pending ]] || break; sleep 15; done
echo "status=$S"; aws ssm get-command-invocation --command-id $C --instance-id $I --query '[StandardOutputContent,StandardErrorContent]' --output text | tail -5
rm -f $D/.params.json; mkdir -p $D/samples/$B; aws s3 cp s3://$BK/phase0/$B.tgz $D/samples/$B.tgz --only-show-errors; tar xzf $D/samples/$B.tgz -C $D/samples/$B; rm $D/samples/$B.tgz

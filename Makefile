.PHONY: eval-run eval-diff eval-judge eval-all promote-baseline eval-label eval-retrieval eval-generation

MVN_EVAL := mvn -q test -Deval.excluded=

eval-run:
	$(MVN_EVAL) -Dtest=EvalRunnerTest -Dgroups=eval-run

eval-diff:
	$(MVN_EVAL) -Dtest=EvalDiffTest -Dgroups=eval-diff

eval-judge:
	$(MVN_EVAL) -Dtest=EvalJudgeTest -Dgroups=eval-judge

eval-all: eval-run eval-diff eval-judge

# Phase 15 — gold pre-label (review gold-labels.json afterwards), retrieval metrics, generation scores
eval-label:
	$(MVN_EVAL) -Dtest=GoldLabelTest -Dgroups=eval-label

eval-retrieval:
	$(MVN_EVAL) -Dtest=RetrievalEvalTest -Dgroups=eval-retrieval

eval-generation:
	$(MVN_EVAL) -Dtest=GenerationEvalTest -Dgroups=eval-generation

promote-baseline:
	@if [ ! -f src/test/resources/eval-reports/latest.json ]; then \
		echo "No latest.json — run 'make eval-run' first."; exit 1; \
	fi
	cp src/test/resources/eval-reports/latest.json src/test/resources/eval-reports/baseline.json
	@echo "Baseline promoted (local only — baseline.json is gitignored)."

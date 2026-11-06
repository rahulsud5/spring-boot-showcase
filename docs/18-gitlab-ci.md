# 18 · GitLab CI/CD pipeline, explained

File: `.gitlab-ci.yml`. Every block carries comments; this guide explains the design.

## 1. Pipeline shape

```
validate        build            test                     quality        package               security                 deploy
validate:pom ─▶ build:compile ─┬▶ test:unit ────────────┬──────────────▶ package:image ─────▶ security:trivy ─▶ deploy:staging ─▶ deploy:production
                               └▶ test:integration (dind)┴▶ quality:sonar   (matrix × 5)        (matrix × 5)       (main + tags)    (tags, manual)
+ package/security/deploy jobs run only on main and release tags (MR pipelines stop after tests and quality)
+ GitLab templates: SAST, Secret Detection, Dependency Scanning (results in the MR security widget)
+ schedule only: security:owasp-nightly
```

`needs:` turns stages into a **DAG**: a job starts as soon as its dependencies finish, not when the
whole previous stage is done. `test:unit` and `test:integration` run in parallel.

## 2. When pipelines run (`workflow:rules`)

```yaml
workflow:
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"          # MR pipelines
    - if: $CI_COMMIT_BRANCH && $CI_OPEN_MERGE_REQUESTS
      when: never                                                # no duplicate branch pipeline when an MR exists
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH               # main
    - if: $CI_COMMIT_TAG =~ /^v\d+\.\d+\.\d+$/                  # release tags
    - if: $CI_PIPELINE_SOURCE == "schedule"                     # nightly scans
  auto_cancel:
    on_new_commit: interruptible                                # a new push cancels outdated pipelines
```

## 3. Speed: cache vs artifacts

| | Cache | Artifacts |
|---|---|---|
| Purpose | Best-effort speed-up **between pipelines** | Guaranteed outputs **within a pipeline** |
| Here | `.m2/repository`, key = hash of `pom.xml` | Compiled `target/` from `build:compile`, test reports, the OWASP report |
| Policy | `pull` for most jobs; `pull-push` only in `build:compile` (via `.maven-cache-writer`) | Expire after a set time |

`MAVEN_OPTS=-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository` because GitLab can only cache paths
inside the project directory. `MAVEN_CLI_OPTS` adds `--batch-mode --no-transfer-progress --fail-at-end -Pci`.

Other speed and stability features: `interruptible: true` by default; `retry` only for
`runner_system_failure` / `stuck_or_timeout_failure` (**never retry test failures**: that hides flaky tests).

## 4. Tests with Testcontainers (Docker-in-Docker)

```yaml
test:integration:
  services:
    - name: docker:27-dind
      alias: docker
      command: ["--tls=false"]
  variables:
    DOCKER_HOST: tcp://docker:2375
    TESTCONTAINERS_HOST_OVERRIDE: docker      # containers live inside dind, reachable at host "docker"
    TESTCONTAINERS_RYUK_DISABLED: "true"      # the dind service dies with the job anyway
  script: mvn $MAVEN_CLI_OPTS verify          # surefire + failsafe + JaCoCo + coverage gate
  coverage: '/Total.*?([0-9]{1,3})%/'
  artifacts:
    reports:
      junit: ["**/target/surefire-reports/TEST-*.xml", "**/target/failsafe-reports/TEST-*.xml"]
      coverage_report: { coverage_format: jacoco, path: "**/target/site/jacoco/jacoco.xml" }
```

* JUnit reports show failed tests directly in the MR; the JaCoCo report shows covered/uncovered lines
  in the MR diff.
* Alternatives to dind: a runner with the host Docker socket (faster, less isolated), or Testcontainers
  Cloud.

## 5. Quality gate

`quality:sonar` runs SonarQube analysis with JaCoCo coverage (only when a `SONAR_TOKEN` variable is configured; `GIT_DEPTH: 0` for blame-based new-code detection, `sonar.qualitygate.wait=true`); `allow_failure: false` means a failed
quality gate (new bugs, vulnerabilities, coverage on new code, duplication) **blocks the merge**.
Combine with GitLab **merge request approval rules** and "pipelines must succeed".

## 6. Building images: `parallel:matrix`

```yaml
package:image:
  parallel:
    matrix:
      - MODULE: [auth-server, api-gateway, order-service, inventory-service, notification-service]
  script:
    - docker buildx build --build-arg MODULE=$MODULE
        --cache-from type=registry,ref=$IMAGE:buildcache --cache-to type=registry,ref=$IMAGE:buildcache,mode=max
        --provenance=true --sbom=true --tag $IMAGE:$IMAGE_TAG --push .
```
One job definition → five parallel jobs. The **registry cache** gives ephemeral runners cache hits.
**Provenance and SBOM attestations** are attached to every image (supply-chain security, SLSA).
The registry login uses `--password-stdin` so the token never appears in the job log or process list.

Images are built for `main` and release tags and tagged with the **immutable commit SHA**; deployments reference that tag (never `latest`).

## 7. Security jobs

| Job | Scans | When |
|---|---|---|
| SAST (template) | Source code patterns (Semgrep-based) | Every pipeline |
| Secret Detection (template) | Committed credentials | Every pipeline |
| Dependency Scanning (template) | Vulnerable libraries | Every pipeline |
| `security:trivy` (matrix) | OS packages + jars inside each image; fails on *fixable* HIGH/CRITICAL (`--ignore-unfixed`) | After `package:image` (main and tags) |
| `security:owasp-nightly` | `mvn -Psecurity` (dependency-check, CVSS ≥ 7 fails) | Scheduled pipeline |

## 8. Deploying to EKS without stored cloud keys (OIDC)

```yaml
.deploy:
  interruptible: false                                  # never cancel a half-finished deployment
  id_tokens:
    AWS_OIDC_TOKEN: { aud: sts.amazonaws.com }          # GitLab signs a short-lived JWT for this job
  before_script:
    - aws sts assume-role-with-web-identity --role-arn "$AWS_DEPLOY_ROLE_ARN" --web-identity-token "$AWS_OIDC_TOKEN" ...
    - aws eks update-kubeconfig --name "$EKS_CLUSTER"
  script:
    - cd deploy/k8s/overlays/$DEPLOY_ENV
    - for s in $SERVICES; do kustomize edit set image "showcase/$s=$CI_REGISTRY_IMAGE/$s:$IMAGE_TAG"; done
    - kubectl apply -k .
    - for s in $SERVICES; do kubectl -n showcase-$DEPLOY_ENV rollout status deploy/$s --timeout=5m; done
```

* The IAM role's trust policy accepts only tokens whose `sub` claim matches this project and a
  protected branch/tag, e.g. `project_path:group/spring-boot-showcase:ref_type:branch:ref:main`.
* `rollout status` makes the job fail if the new pods never become ready.
* `resource_group: staging` / `production` serializes deployments (no two concurrent deploys).
* `environment:` entries give deployment history, rollback buttons and protected-environment approvals.
* `deploy:production` runs only for release tags (`v1.2.3`), as a `when: manual` job: a human approves the release.
  `main` deploys to staging automatically.

## 9. Recommended project settings (outside the YAML)

* Protected branches (`main`) and protected tags (`v*`); merge only through MRs.
* "Pipelines must succeed" + "All threads must be resolved" + approval rules (CODEOWNERS).
* Protected, masked CI/CD variables (`NVD_API_KEY`, `SONAR_TOKEN`, `AWS_DEPLOY_ROLE_ARN`).
* Protected environments for production with required approvers.
* Squash commits or a linear history; Conventional Commits for automatic changelogs.

## 10. Extending it

* **Review apps:** deploy each MR to a temporary namespace (`environment: review/$CI_COMMIT_REF_SLUG`,
  `on_stop` job).
* **Canary or blue/green** with Argo Rollouts or Flagger instead of plain rolling updates.
* **GitOps:** instead of `kubectl apply` from CI, update image tags in a config repo and let Argo CD/Flux sync.
* **Child pipelines** per service (`trigger: include:`) with `rules: changes:` for monorepos that
  grow large.

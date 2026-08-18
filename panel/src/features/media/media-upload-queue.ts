export const MEDIA_UPLOAD_PARALLELISM = 4

/**
 * Runs media uploads with a fixed concurrency bound and preserves input order.
 * After the first failure no new jobs are started; all jobs already in flight
 * settle before the original failure is reported.
 */
export async function runMediaUploadQueue<Job, Result>(
  jobs: readonly Job[],
  upload: (job: Job, index: number) => Promise<Result>,
  parallelism = MEDIA_UPLOAD_PARALLELISM
): Promise<Result[]> {
  if (!Number.isSafeInteger(parallelism) || parallelism < 1) {
    throw new Error("Media upload parallelism must be a positive integer")
  }
  if (jobs.length === 0) return []

  const results = new Array<Result>(jobs.length)
  let nextIndex = 0
  let failed = false
  let firstError: unknown

  async function worker() {
    while (!failed) {
      const index = nextIndex
      nextIndex += 1
      if (index >= jobs.length) return

      try {
        results[index] = await upload(jobs[index]!, index)
      } catch (error) {
        if (!failed) {
          failed = true
          firstError = error
        }
      }
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(parallelism, jobs.length) }, () => worker())
  )
  if (failed) throw firstError
  return results
}

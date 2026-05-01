export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code: string
  ) {
    super(message)
  }
}

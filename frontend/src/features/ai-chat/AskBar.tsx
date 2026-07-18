import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import Markdown from 'react-markdown'
import { api, CryptoPalError } from '../../api/client'
import { ErrorNote, Spinner } from '../../components/ui'

/**
 * The assistant, as a bar pinned to the bottom of the screen.
 *
 * Modelled on the "Ask CMC AI" bar in the CoinMarketCap layout. It is deliberately one
 * question and one answer rather than a running conversation: the backend answers each
 * question from scratch with no memory of the last one, and a chat thread on screen would
 * imply a follow-up like "what about ETH?" would work, when it would not. The UI promises
 * exactly what the server can do.
 */

/** The chips. Each one is a question the account data can genuinely answer. */
const SUGGESTIONS = [
  'What is my portfolio worth?',
  'How did my last trade go?',
  'How has BTC moved recently?',
  'Summarize my recent activity',
]

export function AskBar() {
  const [open, setOpen] = useState(false)
  const [focused, setFocused] = useState(false)
  const [question, setQuestion] = useState('')
  const [asked, setAsked] = useState('')

  const ask = useMutation({
    mutationFn: (q: string) => api.ask(q),
    onMutate: (q) => {
      setAsked(q)
      setOpen(true)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = question.trim()
    if (trimmed) {
      ask.mutate(trimmed)
      setQuestion('')
    }
  }

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-0 z-40 px-4 pb-4">
      <div className="pointer-events-auto mx-auto max-w-3xl">
        {open && (
          <div className="mb-2 max-h-[50vh] overflow-y-auto rounded-xl border border-hairline bg-surface p-4 shadow-2xl shadow-black/50">
            <div className="mb-3 flex items-start justify-between gap-3">
              <p className="text-sm font-medium text-muted">{asked}</p>
              <button
                onClick={() => setOpen(false)}
                className="shrink-0 text-muted hover:text-ink"
                aria-label="Close answer"
              >
                ✕
              </button>
            </div>

            {ask.isPending && <Spinner label="Thinking" />}

            {ask.isError && (
              <ErrorNote
                message={
                  ask.error instanceof CryptoPalError
                    ? ask.error.message
                    : 'The assistant could not answer.'
                }
                onRetry={() => ask.mutate(asked)}
              />
            )}

            {ask.isSuccess && (
              <>
                {/* The answer is Markdown, so it goes through react-markdown. It is never
                    put into innerHTML: it is model output shaped by a user's question, and
                    that is exactly the input you do not hand to a raw HTML sink. */}
                <div className="md text-sm leading-relaxed">
                  <Markdown>{ask.data.answer}</Markdown>
                </div>
                <p className="mt-3 border-t border-hairline pt-2 text-xs text-muted">
                  Informational only, not financial advice. This account is simulated.
                </p>
              </>
            )}
          </div>
        )}

        {/* Only while the input has focus. This bar is fixed to the bottom of the viewport,
            so anything stacked above it sits on top of whatever the user is reading:
            leaving four chips there permanently covered the trade history. Showing them on
            focus keeps the resting state to a single slim bar, and they appear exactly when
            someone is deciding what to ask. onMouseDown rather than onClick, because the
            blur from clicking would otherwise unmount the chip before its click landed. */}
        {focused && !open && (
          <div className="mb-2 flex flex-wrap gap-2">
            {SUGGESTIONS.map((suggestion) => (
              <button
                key={suggestion}
                onMouseDown={(e) => {
                  e.preventDefault()
                  ask.mutate(suggestion)
                }}
                className="rounded-full border border-hairline bg-surface px-3 py-1.5 text-xs text-muted transition hover:border-accent hover:text-ink"
              >
                {suggestion}
              </button>
            ))}
          </div>
        )}

        <form
          onSubmit={submit}
          className="flex items-center gap-2 rounded-xl border border-hairline bg-surface/95 p-2 shadow-2xl shadow-black/50 backdrop-blur focus-within:border-accent"
        >
          <span aria-hidden="true" className="pl-2 text-muted">
            ✦
          </span>
          <input
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            placeholder="Ask LumpaCrypto AI about your account"
            // The backend rejects anything longer, so the field stops at the same number
            // rather than letting someone type an essay and then be told no.
            maxLength={500}
            className="flex-1 bg-transparent py-1.5 text-sm outline-none placeholder:text-muted/70"
          />
          <button
            type="submit"
            disabled={!question.trim() || ask.isPending}
            className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white transition hover:bg-accent-hover disabled:opacity-40"
          >
            Ask
          </button>
        </form>
      </div>
    </div>
  )
}

'use client'

import * as React from 'react'
import { createContext, useCallback, useContext, useState } from 'react'
import { XIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

type ToastVariant = 'success' | 'error'

interface Toast {
  id: number
  message: string
  variant: ToastVariant
}

interface ToastContextValue {
  showToast: (message: string, variant?: ToastVariant) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}

// Module-level dispatcher so non-component code (e.g. a catch block deep in a
// lib function) can raise a toast without threading useToast() through props.
let dispatch: ((message: string, variant?: ToastVariant) => void) | null = null

/** Convenience for `catch` blocks: `.catch((e) => toastError(apiErrorMessage(e)))`. */
export function toastError(message: string): void {
  dispatch?.(message, 'error')
}

export function toastSuccess(message: string): void {
  dispatch?.(message, 'success')
}

let nextId = 0

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const showToast = useCallback((message: string, variant: ToastVariant = 'success') => {
    const id = ++nextId
    setToasts((prev) => [...prev, { id, message, variant }])
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 4000)
  }, [])

  React.useEffect(() => {
    dispatch = showToast
    return () => {
      dispatch = null
    }
  }, [showToast])

  function dismiss(id: number) {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role="alert"
            className={cn(
              'flex items-center justify-between gap-4 rounded-md px-4 py-3 text-sm shadow-lg',
              toast.variant === 'success'
                ? 'bg-status-done text-primary-foreground'
                : 'bg-status-failed text-destructive-foreground',
            )}
          >
            <span>{toast.message}</span>
            <button
              onClick={() => dismiss(toast.id)}
              className="opacity-80 hover:opacity-100"
              aria-label="Dismiss"
            >
              <XIcon className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

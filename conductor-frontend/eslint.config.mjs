import coreWebVitals from 'eslint-config-next/core-web-vitals'
import typescript from 'eslint-config-next/typescript'

export default [
  ...coreWebVitals,
  ...typescript,
  {
    rules: {
      // Downgraded from error to warn during the bulk dep upgrade so pre-existing
      // code doesn't block CI. The stricter defaults arrived with eslint-config-next 16.
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/immutability': 'warn',
      '@typescript-eslint/no-require-imports': 'warn',
      'import/no-anonymous-default-export': 'warn',
    },
  },
]

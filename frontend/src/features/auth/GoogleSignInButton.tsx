import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { loginWithGoogle } from "./api";
import { useAuth } from "../../shared/auth/AuthContext";
import { useI18n } from "../../shared/i18n/I18nContext";
import { Button } from "../../shared/ui/Button";

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (options: {
            client_id: string;
            callback: (response: { credential?: string }) => void;
          }) => void;
          renderButton: (
            element: HTMLElement,
            options: { theme: "outline"; size: "large"; width: number },
          ) => void;
        };
      };
    };
  }
}

const GOOGLE_SCRIPT_ID = "google-identity-services";
const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;

function loadGoogleScript() {
  if (document.getElementById(GOOGLE_SCRIPT_ID)) {
    return Promise.resolve();
  }

  return new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.id = GOOGLE_SCRIPT_ID;
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Google sign-in script failed to load"));
    document.head.appendChild(script);
  });
}

export function GoogleSignInButton() {
  const { t } = useI18n();
  const { login } = useAuth();
  const navigate = useNavigate();
  const buttonRef = useRef<HTMLDivElement | null>(null);
  const [scriptError, setScriptError] = useState<string | null>(null);
  const [buttonRendered, setButtonRendered] = useState(false);

  const mutation = useMutation({
    mutationFn: loginWithGoogle,
    onSuccess: (response) => {
      login(response.accessToken);
      navigate("/", { replace: true });
    },
  });
  const handleCredential = useCallback(
    (credential: string) => {
      mutation.mutate({ credential });
    },
    [mutation.mutate],
  );

  useEffect(() => {
    if (!clientId || !buttonRef.current) {
      return;
    }

    let mounted = true;
    setButtonRendered(false);
    loadGoogleScript()
      .then(() => {
        if (!mounted || !window.google || !buttonRef.current) {
          return;
        }
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => {
            if (response.credential) {
              handleCredential(response.credential);
            }
          },
        });
        buttonRef.current.innerHTML = "";
        const buttonWidth = Math.max(240, Math.min(buttonRef.current.clientWidth || 360, 420));
        window.google.accounts.id.renderButton(buttonRef.current, {
          theme: "outline",
          size: "large",
          width: buttonWidth,
        });
        window.setTimeout(() => {
          if (mounted && buttonRef.current?.querySelector("iframe")) {
            setButtonRendered(true);
          }
          if (mounted && !buttonRef.current?.querySelector("iframe")) {
            setScriptError("Google button did not render. Check the OAuth JavaScript origin.");
          }
        }, 1_500);
      })
      .catch((error: Error) => setScriptError(error.message));

    return () => {
      mounted = false;
    };
  }, [handleCredential]);

  if (!clientId) {
    return (
      <Button disabled type="button" variant="ghost">
        {t("googleUnavailable")}
      </Button>
    );
  }

  return (
    <div className="google-auth">
      <div ref={buttonRef} />
      {!buttonRendered && !scriptError ? <p className="field-hint">Loading Google sign-in...</p> : null}
      {scriptError || mutation.isError ? (
        <p className="form-error">{scriptError ?? mutation.error?.message ?? t("errorTitle")}</p>
      ) : null}
    </div>
  );
}

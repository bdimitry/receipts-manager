import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { z } from "zod";
import { loginUser } from "../api";
import { GoogleSignInButton } from "../GoogleSignInButton";
import { useAuth } from "../../../shared/auth/AuthContext";
import { useI18n } from "../../../shared/i18n/I18nContext";
import { Button } from "../../../shared/ui/Button";

const schema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

type LoginFormValues = z.infer<typeof schema>;

export function LoginPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { login, isAuthenticated } = useAuth();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      email: "",
      password: "",
    },
  });

  const mutation = useMutation({
    mutationFn: loginUser,
    onSuccess: (response) => {
      login(response.accessToken);
      navigate("/", { replace: true });
    },
  });

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="auth-shell">
      <section className="auth-panel auth-panel--hero">
        <p className="auth-panel__eyebrow">{t("overview")}</p>
        <h1>{t("welcomeBack")}</h1>
        <p>{t("authSubtitle")}</p>
        <div className="auth-preview">
          <div className="auth-preview__ring" />
          <div className="auth-preview__copy">
            <strong>{t("spendingByCategory")}</strong>
            <span>{t("recentReceiptsAndReports")}</span>
          </div>
        </div>
      </section>
      <section className="auth-panel">
        <h2>{t("login")}</h2>
        <form className="form-grid" onSubmit={handleSubmit((values) => mutation.mutate(values))}>
          <label className="field">
            <span>{t("email")}</span>
            <input type="email" {...register("email")} />
            {errors.email ? <small>{errors.email.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("password")}</span>
            <input type="password" {...register("password")} />
            {errors.password ? <small>{errors.password.message}</small> : null}
          </label>
          {mutation.isError ? <p className="form-error">{mutation.error.message}</p> : null}
          <Button disabled={mutation.isPending} type="submit">
            {t("login")}
          </Button>
        </form>
        <div className="auth-divider">{t("or")}</div>
        <GoogleSignInButton />
        <Link className="auth-link" to="/register">
          {t("authRegisterLink")}
        </Link>
      </section>
    </div>
  );
}

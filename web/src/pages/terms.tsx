import { SiteFooter } from '@/components/site-footer'
import { SiteHeader } from '@/components/site-header'
import { Badge } from '@/components/ui/badge'
import { Separator } from '@/components/ui/separator'
import { CONTACT_EMAIL } from '@/lib/site'

const EFFECTIVE = 'October 4, 2026'

const sections: { title: string; body: string[] }[] = [
  {
    title: 'What these terms cover',
    body: [
      'These terms cover the AndVibe app as distributed on Google Play, this website, and the hosted build service that comes with the Pro subscription. Together we call them the Service.',
      'The AndVibe source code is licensed separately under the Apache License, Version 2.0. Nothing here limits what that license lets you do with the code. These terms only cover the Service we run and the copy of the app we publish.',
    ],
  },
  {
    title: 'Your projects and your keys',
    body: [
      'Your code is yours. We claim no rights to the repositories you open, the files the agent writes, or the APKs you build.',
      'Model API keys and git tokens stay in encrypted storage on your phone. Model calls go from your phone straight to the provider you pick. Your use of OpenAI, Anthropic, Google, xAI, OpenRouter, Cursor, GitHub, GitLab, Codeberg, Bitbucket, or any custom endpoint is governed by that provider’s terms, and any charges they bill are between you and them.',
      'AI output can be wrong, insecure, or harmful. Review what the agent changes before you commit, push, build, or install it. You are responsible for the apps you build and where you publish them.',
    ],
  },
  {
    title: 'Hosted builds',
    body: [
      'With Pro, the app uploads a zip of your project to our build server, runs Gradle, and returns the APK. The upload does not include your model API key. Sources are deleted when the build finishes. The tail of the Gradle log may be kept in our service logs to help diagnose failures.',
      'Builds run one at a time with time and size limits, currently 32 MB per upload and about an hour per build. We may change these limits, and the service may be unavailable at times.',
    ],
  },
  {
    title: 'Acceptable use',
    body: [
      'Do not use the Service to build malware, spyware, or apps meant to deceive or harm people; to infringe someone else’s rights; to break the law; or to attack, overload, or get around the limits of our servers. We may suspend hosted builds for an account that does.',
    ],
  },
  {
    title: 'Subscription and billing',
    body: [
      'Pro is a recurring subscription sold through Google Play. Google charges your Play account at the price shown before you confirm, and the subscription renews automatically at the end of each period until you cancel.',
      'Cancel any time in the Google Play app under Payments & subscriptions. You keep Pro until the end of the period you already paid for. Refunds are handled under Google Play’s refund policy.',
      'If we change the price, Google Play will tell you before it applies to your subscription, and you can cancel before then.',
      'The free version keeps every app feature. Pro adds hosted builds, so you do not need your own Cloud Run.',
    ],
  },
  {
    title: 'No warranty',
    body: [
      'The Service is provided as is and as available, without warranties of any kind, to the extent the law allows. We do not promise that builds will succeed, that the agent will produce working code, or that the Service will be uninterrupted or error free.',
    ],
  },
  {
    title: 'Limitation of liability',
    body: [
      'To the extent the law allows, AndVibe is not liable for indirect, incidental, or consequential damages, or for lost data, code, profits, or provider charges. Our total liability for any claim about the Service is limited to what you paid us for it in the 12 months before the claim.',
      'Some places do not allow these limits. Where they do not, they apply only as far as the law permits, and nothing here takes away rights you have as a consumer.',
    ],
  },
  {
    title: 'Ending use',
    body: [
      'You can stop using the Service at any time by cancelling Pro and uninstalling the app. We may stop offering the Service or any part of it. If we shut down hosted builds, active subscribers will be told in advance and will not be charged for periods we cannot provide.',
    ],
  },
  {
    title: 'Changes',
    body: [
      'We may update these terms. The date at the top shows the latest version. If a change is significant, we will say so in the app or on this site before it takes effect. Using the Service after that means you accept the new terms.',
    ],
  },
]

export function Terms() {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-3xl px-5 py-16">
        <Badge variant="secondary" className="font-mono">
          Effective {EFFECTIVE}
        </Badge>
        <h1 className="mt-4 text-3xl font-semibold tracking-tight">Terms of Service</h1>
        <p className="mt-3 text-muted-foreground">
          The rules for using the AndVibe app, this site, and hosted builds. By using them you agree
          to these terms.
        </p>

        <Separator className="my-10" />

        <div className="space-y-10">
          {sections.map((section) => (
            <section key={section.title} className="space-y-3">
              <h2 className="text-xl font-semibold">{section.title}</h2>
              {section.body.map((paragraph) => (
                <p key={paragraph} className="text-muted-foreground">
                  {paragraph}
                </p>
              ))}
            </section>
          ))}
          <section className="space-y-3">
            <h2 className="text-xl font-semibold">Contact</h2>
            <p className="text-muted-foreground">
              {CONTACT_EMAIL ? (
                <>
                  Questions about these terms go to{' '}
                  <a href={`mailto:${CONTACT_EMAIL}`} className="text-link hover:underline">
                    {CONTACT_EMAIL}
                  </a>
                  .
                </>
              ) : (
                'Questions about these terms go to the developer email on the AndVibe Google Play listing.'
              )}
            </p>
          </section>
        </div>
      </main>
      <SiteFooter />
    </>
  )
}

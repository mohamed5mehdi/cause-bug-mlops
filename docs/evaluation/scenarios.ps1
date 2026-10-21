# ============================================================================
# Scenarios de panne P1, P2, P3 et demande de diagnostic (evaluation J9).
#
# Usage, depuis la racine du depot, pile demarree (docker compose up -d) :
#   . .\docs\evaluation\scenarios.ps1        # charge les fonctions (point + espace)
#   $id = Invoke-PanneP1                      # provoque P1, renvoie le businessCorrelationId
#   Invoke-Diagnostic $id                     # demande le diagnostic et l'affiche
#
# Teste sous Windows PowerShell 5.1 (PowerShell 7 non verifie). Les pannes P1 et P2
# sont desarmees en fin de scenario, meme en cas d'erreur.
# ============================================================================

$script:ERP   = 'http://localhost:8080/api'
$script:AIOPS = 'http://localhost:8000'
# Fichier Compose localise depuis l'emplacement du script : fonctionne quel que soit le dossier courant.
$script:COMPOSE = Join-Path $PSScriptRoot '..\..\docker-compose.yml'

function Invoke-Api {
    param(
        [Parameter(Mandatory)] [string] $Methode,
        [Parameter(Mandatory)] [string] $Url,
        $Corps = $null,
        $Utilisateur = $null,
        [int] $DelaiSecondes = 30
    )
    $entetes = @{}
    if ($null -ne $Utilisateur) { $entetes['X-Utilisateur-Id'] = "$Utilisateur" }
    $parametres = @{ Method = $Methode; Uri = $Url; Headers = $entetes; UseBasicParsing = $true
                     TimeoutSec = $DelaiSecondes; ContentType = 'application/json; charset=utf-8' }
    if ($null -ne $Corps) {
        $parametres.Body = [Text.Encoding]::UTF8.GetBytes(($Corps | ConvertTo-Json -Depth 6 -Compress))
    }
    try {
        $reponse = Invoke-WebRequest @parametres
        $code = [int] $reponse.StatusCode
        # Decodage UTF-8 explicite : sans charset dans la reponse, PowerShell 5.1 lit en Latin-1.
        $contenu = [Text.Encoding]::UTF8.GetString($reponse.RawContentStream.ToArray())
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $code = [int] $_.Exception.Response.StatusCode
        $contenu = $_.ErrorDetails.Message
        # Windows PowerShell 5.1 ne remplit pas toujours ErrorDetails : lecture du flux.
        if (-not $contenu -and $_.Exception.Response -is [System.Net.HttpWebResponse]) {
            $flux = $_.Exception.Response.GetResponseStream()
            if ($flux.CanSeek) { $flux.Position = 0 }
            $contenu = (New-Object System.IO.StreamReader($flux, [Text.Encoding]::UTF8)).ReadToEnd()
        }
    }
    $json = $null
    if ($contenu) { $json = $contenu | ConvertFrom-Json }
    [pscustomobject] @{ Code = $code; Json = $json }
}

function Get-ValeurSql([string] $Requete) {
    $valeur = docker compose -f $script:COMPOSE exec -T postgres psql -U postgres -d causebugmlops -tAc $Requete
    if ($LASTEXITCODE -ne 0) { throw "Requete SQL en echec : $Requete" }
    "$valeur".Trim()
}

function Get-IdsDemo {
    $ids = @{}
    foreach ($paire in @(('demandeur', 'mehdi.demandeur@demo.local'), ('manager', 'manager.n1@demo.local'),
                         ('acheteur', 'acheteur@demo.local'), ('amg', 'amg@demo.local'), ('daf', 'daf@demo.local'))) {
        $ids[$paire[0]] = [long] (Get-ValeurSql "SELECT id FROM erp.utilisateur WHERE email = '$($paire[1])'")
    }
    $ids['fournisseur'] = [long] (Get-ValeurSql "SELECT id FROM erp.fournisseur WHERE raison_sociale = 'Fournisseur Demo Informatique'")
    $ids['mat'] = [long] (Get-ValeurSql "SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-MAT'")
    $ids['acc'] = [long] (Get-ValeurSql "SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-ACC'")
    $ids
}

function Assert-Code($Resultat, [int] $Attendu, [string] $Etape) {
    if ($Resultat.Code -ne $Attendu) {
        throw "$Etape : HTTP $($Resultat.Code) au lieu de $Attendu. Reponse : $($Resultat.Json | ConvertTo-Json -Compress)"
    }
}

# Cree une demande et la conduit jusqu'a VALIDEE_TECHNIQUE ; renvoie la demande creee.
function New-DemandeValidee($Ids, [string] $Article) {
    $r = Invoke-Api POST "$script:ERP/demandes" -Utilisateur $Ids.demandeur -Corps @{
        justification = "Evaluation J9"; lignes = @(@{ designationLibre = $Article; quantite = 1 }) }
    Assert-Code $r 201 'Creation'
    $demande = $r.Json
    $base = "$script:ERP/demandes/$($demande.id)"
    Assert-Code (Invoke-Api POST "$base/soumission") 200 'Soumission'
    Assert-Code (Invoke-Api POST "$base/validation-n1" -Utilisateur $Ids.manager -Corps @{ valider = $true }) 200 'Validation N+1'
    Assert-Code (Invoke-Api POST "$base/validation-technique") 200 'Validation technique'
    $demande
}

function Get-CorpsValorisation($Ids, $Demande, [long] $SousFamille, $PrixHt) {
    @{ tauxTva = 0.20; fournisseurId = $Ids.fournisseur; sousFamilleId = $SousFamille
       prix = @(@{ ligneId = $Demande.lignes[0].id; prixUnitaireHt = $PrixHt }) }
}

# P1 (BASE_DE_DONNEES) : verrou de l'enveloppe retenu par une cloture concurrente -> 503.
function Invoke-PanneP1 {
    $ids = Get-IdsDemo
    $demande = New-DemandeValidee $ids 'Clavier'
    Assert-Code (Invoke-Api POST "$script:ERP/pannes/P1") 200 'Armement P1'
    try {
        $r = Invoke-Api POST "$script:ERP/demandes/$($demande.id)/valorisation" -Corps (Get-CorpsValorisation $ids $demande $ids.mat 10)
    } finally {
        $null = Invoke-Api DELETE "$script:ERP/pannes/P1"
    }
    Assert-Code $r 503 'Valorisation sous P1'
    Write-Host "P1 provoquee : 503 $($r.Json.code). Attente de la liberation du verrou (7 s)..."
    Start-Sleep -Seconds 7
    $demande.id
}

# P2 (CODE_JAVA) : TVA lue comme un pourcentage -> TTC = HT x 21, sans aucune erreur.
function Invoke-PanneP2 {
    $ids = Get-IdsDemo
    $demande = New-DemandeValidee $ids 'Souris'
    Assert-Code (Invoke-Api POST "$script:ERP/pannes/P2") 200 'Armement P2'
    try {
        $r = Invoke-Api POST "$script:ERP/demandes/$($demande.id)/valorisation" -Corps (Get-CorpsValorisation $ids $demande $ids.mat 10)
    } finally {
        $null = Invoke-Api DELETE "$script:ERP/pannes/P2"
    }
    Assert-Code $r 200 'Valorisation sous P2'
    Write-Host "P2 provoquee : HT $($r.Json.montantHt), TTC $($r.Json.montantTtc) (attendu sans panne : HT x 1,20)."
    $demande.id
}

# P3 (FLUX_METIER) : defaut T1a - validee jusqu'au DG sans valorisation, puis PO en 500.
function Invoke-PanneP3 {
    $ids = Get-IdsDemo
    $demande = New-DemandeValidee $ids "Station d'accueil"
    $base = "$script:ERP/demandes/$($demande.id)"
    $disponible = [decimal] (Get-ValeurSql "SELECT montant_alloue - montant_engage FROM erp.sous_famille_budgetaire WHERE id = $($ids.acc)")
    $prix = [math]::Floor($disponible / 1.2) + 10
    $corps = Get-CorpsValorisation $ids $demande $ids.acc $prix

    $r = Invoke-Api POST "$base/valorisation" -Corps $corps
    Assert-Code $r 422 'Valorisation initiale (refus budgetaire attendu)'
    $r = Invoke-Api POST "$base/ajustements" -Utilisateur $ids.acheteur -Corps @{
        type = 'SOUS_FAMILLE'; montant = $r.Json.montantManquant; sourceSousFamilleId = $ids.mat
        cibleSousFamilleId = $ids.acc; justification = 'Enveloppe accessoires insuffisante' }
    Assert-Code $r 201 'Soumission de l''ajustement'
    $baseAj = "$base/ajustements/$($r.Json.id)"
    Assert-Code (Invoke-Api POST "$baseAj/decision-daf" -Utilisateur $ids.daf -Corps @{ valider = $true }) 200 'Decision DAF'
    Assert-Code (Invoke-Api POST "$baseAj/confirmation-acheteur" -Utilisateur $ids.acheteur -Corps @{ justification = 'Ajustement accorde' }) 200 'Confirmation acheteur'
    Assert-Code (Invoke-Api POST "$baseAj/finalisation-amg" -Utilisateur $ids.amg) 200 'Finalisation AMG'
    Assert-Code (Invoke-Api POST "$base/validation-amg" -Utilisateur $ids.amg -Corps @{ valider = $true }) 200 'Validation AMG'
    Assert-Code (Invoke-Api POST "$base/validation-daf" -Utilisateur $ids.daf -Corps @{ valider = $true }) 200 'Validation DAF'
    Assert-Code (Invoke-Api POST "$base/validation-dg") 200 'Validation DG'
    $r = Invoke-Api POST "$base/purchase-order"
    Assert-Code $r 500 'Generation du PO (echec attendu)'
    Write-Host "P3 provoquee : demande VALIDEE_DG sans montant, PO en 500 ($($r.Json.code))."
    $demande.id
}

# Temoin sain : demande valorisee normalement (TTC = HT x 1,20), en attente de validation
# AMG. Aucune anomalie : un bon diagnostic ne doit pas conclure avec assurance a une panne.
function Invoke-Temoin {
    $ids = Get-IdsDemo
    $demande = New-DemandeValidee $ids 'Ecran'
    $r = Invoke-Api POST "$script:ERP/demandes/$($demande.id)/valorisation" -Corps (Get-CorpsValorisation $ids $demande $ids.mat 10)
    Assert-Code $r 200 'Valorisation du temoin'
    Write-Host "Temoin sain cree : HT $($r.Json.montantHt), TTC $($r.Json.montantTtc), statut $($r.Json.statut)."
    $demande.id
}

# Demande le diagnostic et affiche les valeurs a reporter dans la grille.
function Invoke-Diagnostic([Parameter(Mandatory)] [string] $CorrelationId) {
    Start-Sleep -Seconds 2   # laisse l'ingestion copier les dernieres traces
    $r = Invoke-Api POST "$script:AIOPS/diagnose/$CorrelationId" -DelaiSecondes 140
    if ($r.Code -ne 200) {
        Write-Host "Echec technique : HTTP $($r.Code) $($r.Json.detail)" -ForegroundColor Yellow
        return
    }
    $d = $r.Json
    [pscustomobject] @{
        horodatage        = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
        correlation_id    = $d.business_correlation_id
        composant         = $d.composant
        confiance         = $d.confiance
        preuves           = ($d.preuves -join ' ')
        preuves_rejetees  = ($d.preuves_rejetees -join ' ')
        incidents_proches = $d.contexte.incidents_similaires
        modele            = $d.modele
        latence_ms        = $d.latence_ms
        cause_racine      = $d.cause_racine
    } | Format-List
}

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const rootDir = path.resolve(__dirname, '..');
const versionInfoPath = path.join(rootDir, 'version_info.json');
const updateJsonPath = path.join(rootDir, 'mobile-update.json');

const githubRunNumber = process.env.GITHUB_RUN_NUMBER || '1';
const versionCode = 9934841 + parseInt(githubRunNumber, 10);

let versionName = '1.0.7';
let changelog = 'Melhorias de desempenho e novidades no servidor!';

if (fs.existsSync(versionInfoPath)) {
    try {
        const info = JSON.parse(fs.readFileSync(versionInfoPath, 'utf8'));
        if (info.versionName) versionName = info.versionName;
        if (info.changelog) changelog = info.changelog;
    } catch (e) {
        console.error('Aviso ao ler version_info.json:', e.message);
    }
}

const manifest = {
    versionCode: versionCode,
    versionName: versionName,
    title: `Rede Eclypse Mobile v${versionName}`,
    changelog: changelog,
    apkUrl: 'https://github.com/sleexzx11/rede-eclypse-mobile/releases/download/v1.0.0/Rede-Eclypse-Mobile.apk'
};

fs.writeFileSync(updateJsonPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8');
console.log('============================================================');
console.log(' Manifesto mobile-update.json gerado com sucesso:');
console.log(JSON.stringify(manifest, null, 2));
console.log('============================================================\n');

try {
    execSync('git config user.name "github-actions[bot]"', { cwd: rootDir, stdio: 'inherit' });
    execSync('git config user.email "github-actions[bot]@users.noreply.github.com"', { cwd: rootDir, stdio: 'inherit' });
    execSync('git add mobile-update.json', { cwd: rootDir, stdio: 'inherit' });
    execSync(`git commit -m "chore(release): sincroniza mobile-update.json v${versionName} (code ${versionCode}) [skip ci]"`, { cwd: rootDir, stdio: 'inherit' });
    execSync('git push origin main', { cwd: rootDir, stdio: 'inherit' });
    console.log('Manifesto publicado no GitHub com sucesso!');
} catch (e) {
    console.log('Nenhuma alteracao para commitar no mobile-update.json ou push desnecessario.');
}
